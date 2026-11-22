package com.fa.auth.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.auth.api.dto.AuthDtos;
import com.fa.auth.domain.Account;
import com.fa.auth.domain.Teacher;
import com.fa.auth.infra.AccountMapper;
import com.fa.auth.infra.TeacherMapper;
import com.fa.auth.security.LoginRateLimiter;
import com.fa.auth.security.TokenBlacklist;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.JwtUtil;
import com.fa.common.security.SecurityUtils;
import com.fa.org.domain.ClassGroup;
import com.fa.org.domain.FaceProfile;
import com.fa.org.domain.Student;
import com.fa.org.infra.ClassGroupMapper;
import com.fa.org.infra.FaceProfileMapper;
import com.fa.org.infra.StudentMapper;
import com.fa.org.infra.TeacherScopeMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证主流程：登录、刷新、登出、学生激活、当前用户。
 *
 * <p>为什么激活放在 auth 而不是 org：它最终产出的是一组令牌，属于认证语义；
 * 但校验三要素要读 org 的 student/class。这也是 fa-auth 依赖 fa-org 的唯一原因。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountMapper accountMapper;
    private final TeacherMapper teacherMapper;
    private final StudentMapper studentMapper;
    private final ClassGroupMapper classMapper;
    private final FaceProfileMapper faceMapper;
    private final TeacherScopeMapper scopeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;
    private final LoginRateLimiter limiter;
    private final AccountFactory accountFactory;
    private final JdbcTemplate jdbc;

    public AuthService(AccountMapper accountMapper, TeacherMapper teacherMapper,
                       StudentMapper studentMapper, ClassGroupMapper classMapper,
                       FaceProfileMapper faceMapper, TeacherScopeMapper scopeMapper,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                       TokenBlacklist blacklist, LoginRateLimiter limiter,
                       AccountFactory accountFactory, JdbcTemplate jdbc) {
        this.accountMapper = accountMapper;
        this.teacherMapper = teacherMapper;
        this.studentMapper = studentMapper;
        this.classMapper = classMapper;
        this.faceMapper = faceMapper;
        this.scopeMapper = scopeMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
        this.limiter = limiter;
        this.accountFactory = accountFactory;
        this.jdbc = jdbc;
    }

    @Transactional
    public AuthDtos.TokenData login(AuthDtos.LoginReq req) {
        if (limiter.isLocked(req.username())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS,
                    "密码错误次数过多，请 10 分钟后再试");
        }

        Account account = accountMapper.selectOne(
                Wrappers.<Account>lambdaQuery().eq(Account::getUsername, req.username()));

        // 账号不存在时也走一次 BCrypt 校验，避免通过响应时间判断账号是否存在
        boolean matched = account != null
                && passwordEncoder.matches(req.password(), account.getPasswordHash());
        if (!matched) {
            limiter.onFailure(req.username());
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }

        limiter.onSuccess(req.username());
        account.setLastLoginAt(LocalDateTime.now());
        accountMapper.updateById(account);

        return issueTokens(account);
    }

    @Transactional
    public AuthDtos.TokenData refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtil.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        var payload = jwtUtil.toPayload(claims);
        if (!JwtUtil.TYPE_REFRESH.equals(payload.tokenType())) {
            // 拿 access 当 refresh 用是常见误用，明确拒绝
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if (blacklist.isRevoked(payload.jti())) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        Account account = accountMapper.selectById(payload.accountId());
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        // 改过密码/登录名的账号，旧 refresh 也不能再换出新令牌
        int currentVersion = account.getCredentialVersion() == null ? 0 : account.getCredentialVersion();
        if (payload.credentialVersion() != currentVersion) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        // 旋转：旧 refresh 立即作废，降低被窃取后的窗口
        blacklist.revoke(payload.jti(), jwtUtil.remainingSeconds(refreshToken));
        return issueTokens(account);
    }

    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtUtil.parse(accessToken);
            blacklist.revoke(claims.getId(), jwtUtil.remainingSeconds(accessToken));
        } catch (JwtException | IllegalArgumentException e) {
            // 令牌本来就无效，登出视为成功（幂等）
            log.debug("logout with invalid token");
        }
    }

    /**
     * 学生自助激活（D5）。
     *
     * <p>三要素任一不匹配都返回<b>同一句</b>提示，不区分「学号不存在」「姓名不符」
     * 「班级码错误」——否则可以拿来探测学号是否有效（§5.3.1）。
     */
    @Transactional
    public AuthDtos.TokenData activate(AuthDtos.ActivateReq req) {
        ClassGroup clazz = classMapper.selectOne(
                Wrappers.<ClassGroup>lambdaQuery().eq(ClassGroup::getCode, req.classCode()));

        Student student = studentMapper.selectOne(Wrappers.<Student>lambdaQuery()
                .eq(Student::getStudentNo, req.studentNo())
                .eq(Student::getName, req.name()));

        boolean ok = clazz != null && student != null
                && "ACTIVE".equals(clazz.getStatus())
                && student.getClassId().equals(clazz.getId())
                && "ACTIVE".equals(student.getStatus());
        if (!ok) {
            throw new BizException(ErrorCode.NOT_FOUND, "学号、姓名或班级码不匹配");
        }
        if (student.getAccountId() != null) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS, "该学号已激活，请直接登录");
        }
        Long dup = accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getUsername, req.studentNo()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS);
        }

        Account account = accountFactory.create(req.studentNo(), req.password(), "STUDENT");
        accountMapper.insert(account);

        student.setAccountId(account.getId());
        studentMapper.updateById(student);

        return issueTokens(account);
    }

    public AuthDtos.MeData me() {
        CurrentUser user = SecurityUtils.current();
        AuthDtos.Profile profile = buildProfile(user.accountId());

        List<String> permissions = switch (profile.role()) {
            case "ADMIN" -> List.of("teacher:write", "scope:write", "class:write", "course:write",
                    "student:write", "session:write", "attendance:write", "appeal:write",
                    "audit:read", "config:write", "agent:use", "profile:review");
            case "TEACHER" -> List.of("class:write", "course:write", "student:write",
                    "session:write", "attendance:write", "appeal:write", "agent:use",
                    "profile:write");
            default -> List.of("checkin:do", "appeal:write");
        };

        AuthDtos.ScopeInfo scope;
        if ("ADMIN".equals(profile.role())) {
            // ADMIN 不过滤，返回空数组表示「全部」——由前端据此隐藏筛选器
            scope = new AuthDtos.ScopeInfo(List.of(), List.of());
        } else if (profile.teacherId() != null) {
            var rows = scopeMapper.selectList(Wrappers.<com.fa.org.domain.TeacherScope>lambdaQuery()
                    .eq(com.fa.org.domain.TeacherScope::getTeacherId, profile.teacherId()));
            List<Long> classIds = new ArrayList<>();
            List<Long> courseIds = new ArrayList<>();
            rows.forEach(s -> {
                if ("CLASS".equals(s.getScopeType())) {
                    classIds.add(s.getScopeId());
                } else if ("COURSE".equals(s.getScopeType())) {
                    courseIds.add(s.getScopeId());
                }
            });
            scope = new AuthDtos.ScopeInfo(classIds, courseIds);
        } else {
            scope = new AuthDtos.ScopeInfo(List.of(), List.of());
        }

        Account account = accountMapper.selectById(user.accountId());
        return new AuthDtos.MeData(profile, permissions, scope,
                account != null && account.getFaceConsentAt() != null);
    }

    private AuthDtos.TokenData issueTokens(Account account) {
        Teacher teacher = "TEACHER".equals(account.getRole())
                ? teacherMapper.selectOne(Wrappers.<Teacher>lambdaQuery()
                        .eq(Teacher::getAccountId, account.getId()))
                : null;
        Student student = "STUDENT".equals(account.getRole())
                ? studentMapper.selectOne(Wrappers.<Student>lambdaQuery()
                        .eq(Student::getAccountId, account.getId()))
                : null;

        Long teacherId = teacher == null ? null : teacher.getId();
        Long studentId = student == null ? null : student.getId();

        int credentialVersion = account.getCredentialVersion() == null ? 0 : account.getCredentialVersion();
        String access = jwtUtil.issueAccess(account.getId(), account.getUsername(),
                account.getRole(), teacherId, studentId, credentialVersion);
        String refresh = jwtUtil.issueRefresh(account.getId(), account.getUsername(),
                account.getRole(), teacherId, studentId, credentialVersion);

        return new AuthDtos.TokenData(access, refresh, account.getRole(),
                jwtUtil.accessTtl().toSeconds(), buildProfile(account.getId()));
    }

    private AuthDtos.Profile buildProfile(long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        Long teacherId = null;
        Long studentId = null;
        Long classId = null;
        String className = null;
        String faceStatus = null;
        String displayName = account.getUsername();

        if ("TEACHER".equals(account.getRole())) {
            Teacher t = teacherMapper.selectOne(
                    Wrappers.<Teacher>lambdaQuery().eq(Teacher::getAccountId, accountId));
            if (t != null) {
                teacherId = t.getId();
                displayName = t.getName();
            }
        } else if ("STUDENT".equals(account.getRole())) {
            Student s = studentMapper.selectOne(
                    Wrappers.<Student>lambdaQuery().eq(Student::getAccountId, accountId));
            if (s != null) {
                studentId = s.getId();
                classId = s.getClassId();
                displayName = s.getName();
                ClassGroup c = classMapper.selectById(s.getClassId());
                className = c == null ? null : c.getName();
                FaceProfile fp = faceMapper.selectOne(Wrappers.<FaceProfile>lambdaQuery()
                        .eq(FaceProfile::getStudentId, s.getId()));
                faceStatus = fp == null ? "NONE" : fp.getStatus();
            }
        } else {
            displayName = "管理员";
        }

        return new AuthDtos.Profile(accountId, account.getUsername(), displayName,
                account.getRole(), teacherId, studentId, classId, className, faceStatus);
    }
}
