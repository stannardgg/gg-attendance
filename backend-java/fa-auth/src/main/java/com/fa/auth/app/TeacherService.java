package com.fa.auth.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.auth.api.dto.TeacherDtos;
import com.fa.auth.domain.Account;
import com.fa.auth.domain.Teacher;
import com.fa.auth.infra.AccountMapper;
import com.fa.auth.infra.TeacherMapper;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教师 CRUD。**仅 ADMIN**（§3.1：教师信息 CRUD 是管理员独占能力）。
 *
 * <p>权限在这里再判一次，不完全依赖控制器注解：控制器上是
 * {@code @PreAuthorize}，一旦有人为了调试临时放开，这里还能兜住。
 */
@Service
public class TeacherService {

    private final TeacherMapper teacherMapper;
    private final AccountMapper accountMapper;
    private final AccountFactory accountFactory;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;

    public TeacherService(TeacherMapper teacherMapper, AccountMapper accountMapper,
                          AccountFactory accountFactory, AuditWriter audit, JdbcTemplate jdbc) {
        this.teacherMapper = teacherMapper;
        this.accountMapper = accountMapper;
        this.accountFactory = accountFactory;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public PageResult<TeacherDtos.Resp> list(PageQuery query, String keyword) {
        requireAdmin();

        var wrapper = Wrappers.<Teacher>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Teacher::getName, kw).or().like(Teacher::getTeacherNo, kw));
        }
        wrapper.orderByDesc(Teacher::getId);

        IPage<Teacher> page = teacherMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    @Transactional
    public TeacherDtos.Resp create(TeacherDtos.CreateReq req) {
        requireAdmin();

        Long dupNo = teacherMapper.selectCount(
                Wrappers.<Teacher>lambdaQuery().eq(Teacher::getTeacherNo, req.teacherNo()));
        if (dupNo != null && dupNo > 0) {
            throw new BizException(ErrorCode.CODE_DUPLICATED, "工号已存在");
        }
        Long dupAccount = accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getUsername, req.teacherNo()));
        if (dupAccount != null && dupAccount > 0) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS);
        }

        Account account = accountFactory.create(req.teacherNo(), req.password(), "TEACHER");
        accountMapper.insert(account);

        Teacher teacher = new Teacher();
        teacher.setAccountId(account.getId());
        teacher.setTeacherNo(req.teacherNo());
        teacher.setName(req.name());
        teacher.setDepartment(req.department());
        teacher.setTitle(req.title());
        teacher.setStatus("ACTIVE");
        teacherMapper.insert(teacher);

        audit.write("TEACHER_CREATE", "TEACHER", teacher.getId(), "新增教师 " + teacher.getTeacherNo());
        return toRespList(List.of(teacher)).get(0);
    }

    @Transactional
    public TeacherDtos.Resp update(long id, TeacherDtos.UpdateReq req) {
        requireAdmin();
        Teacher teacher = teacherMapper.selectById(id);
        if (teacher == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师不存在");
        }
        if (req.name() != null) {
            teacher.setName(req.name());
        }
        if (req.department() != null) {
            teacher.setDepartment(req.department());
        }
        if (req.title() != null) {
            teacher.setTitle(req.title());
        }
        if (req.status() != null) {
            teacher.setStatus(req.status());
            // 教师停用要同时停用账号，否则仍能登录拿到令牌
            Account account = accountMapper.selectById(teacher.getAccountId());
            if (account != null) {
                account.setStatus("ACTIVE".equals(req.status()) ? "ACTIVE" : "DISABLED");
                accountMapper.updateById(account);
            }
        }
        teacherMapper.updateById(teacher);
        audit.write("TEACHER_UPDATE", "TEACHER", id, "编辑教师 " + teacher.getTeacherNo());
        return toRespList(List.of(teacher)).get(0);
    }

    @Transactional
    public void delete(long id) {
        requireAdmin();
        Teacher teacher = teacherMapper.selectById(id);
        if (teacher == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师不存在");
        }
        Long sessions = jdbc.queryForObject(
                "select count(*) from checkin_session where teacher_id = ?", Long.class, id);
        if (sessions != null && sessions > 0) {
            // 有历史会话就不能物理删除，否则签到记录里的 teacher_id 变成悬空引用
            throw new BizException(ErrorCode.FORBIDDEN, "该教师已有签到记录，不能删除，请改为停用");
        }
        teacherMapper.deleteById(id);
        if (teacher.getAccountId() != null) {
            accountMapper.deleteById(teacher.getAccountId());
        }
        audit.write("TEACHER_DELETE", "TEACHER", id, "删除教师 " + teacher.getTeacherNo());
    }

    private void requireAdmin() {
        if (!SecurityUtils.current().isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    private List<TeacherDtos.Resp> toRespList(List<Teacher> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> ids = records.stream().map(Teacher::getId).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, long[]> counts = new HashMap<>();
        jdbc.query("select teacher_id, scope_type, count(*) c from teacher_scope "
                        + "where teacher_id in (" + placeholders + ") group by teacher_id, scope_type",
                rs -> {
                    long[] pair = counts.computeIfAbsent(rs.getLong("teacher_id"), k -> new long[2]);
                    if ("CLASS".equals(rs.getString("scope_type"))) {
                        pair[0] = rs.getLong("c");
                    } else {
                        pair[1] = rs.getLong("c");
                    }
                },
                ids.toArray());

        List<TeacherDtos.Resp> list = new ArrayList<>(records.size());
        for (Teacher t : records) {
            long[] pair = counts.getOrDefault(t.getId(), new long[2]);
            list.add(new TeacherDtos.Resp(t.getId(), t.getTeacherNo(), t.getName(),
                    t.getDepartment(), t.getTitle(), t.getStatus(), pair[0], pair[1]));
        }
        return list;
    }
}
