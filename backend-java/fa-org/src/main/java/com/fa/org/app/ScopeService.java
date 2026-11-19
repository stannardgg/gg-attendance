package com.fa.org.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.domain.TeacherScope;
import com.fa.org.infra.NameLookup;
import com.fa.org.infra.TeacherScopeMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教师授权范围维护（仅 ADMIN）。
 *
 * <p>这一层不做数据范围过滤——ADMIN 本身不受限，而 TEACHER 根本进不来（控制器上
 * 有 {@code @PreAuthorize("hasRole('ADMIN')")}）。如果有人把注解去掉了，
 * 这里会直接拒绝非 ADMIN，作为第二道。
 */
@Service
public class ScopeService {

    private final TeacherScopeMapper scopeMapper;
    private final NameLookup nameLookup;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    public ScopeService(TeacherScopeMapper scopeMapper, NameLookup nameLookup,
                        JdbcTemplate jdbc, AuditWriter audit) {
        this.scopeMapper = scopeMapper;
        this.nameLookup = nameLookup;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<ScopeDtos.Resp> list(Long teacherId) {
        var wrapper = Wrappers.<TeacherScope>lambdaQuery();
        if (teacherId != null) {
            wrapper.eq(TeacherScope::getTeacherId, teacherId);
        }
        wrapper.orderByAsc(TeacherScope::getTeacherId).orderByAsc(TeacherScope::getScopeType);
        return toRespList(scopeMapper.selectList(wrapper));
    }

    @Transactional
    public ScopeDtos.Resp grant(ScopeDtos.CreateReq req) {
        requireAdmin();
        if (!"CLASS".equals(req.scopeType()) && !"COURSE".equals(req.scopeType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "scopeType 只能是 CLASS 或 COURSE");
        }
        Long teacher = jdbc.queryForObject(
                "select count(*) from teacher where id = ?", Long.class, req.teacherId());
        if (teacher == null || teacher == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师不存在");
        }
        String table = "CLASS".equals(req.scopeType()) ? "class_group" : "course";
        Long target = jdbc.queryForObject(
                "select count(*) from " + table + " where id = ?", Long.class, req.scopeId());
        if (target == null || target == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "被授权对象不存在");
        }

        Long dup = scopeMapper.selectCount(Wrappers.<TeacherScope>lambdaQuery()
                .eq(TeacherScope::getTeacherId, req.teacherId())
                .eq(TeacherScope::getScopeType, req.scopeType())
                .eq(TeacherScope::getScopeId, req.scopeId()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CODE_DUPLICATED, "该授权已存在");
        }

        TeacherScope entity = new TeacherScope();
        entity.setTeacherId(req.teacherId());
        entity.setScopeType(req.scopeType());
        entity.setScopeId(req.scopeId());
        entity.setGrantedBy(SecurityUtils.currentAccountId());
        scopeMapper.insert(entity);

        audit.write("SCOPE_GRANT", "TEACHER", req.teacherId(),
                "授权 " + req.scopeType() + ":" + req.scopeId());
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public void revoke(long id) {
        requireAdmin();
        TeacherScope entity = scopeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "授权记录不存在");
        }
        scopeMapper.deleteById(id);
        audit.write("SCOPE_REVOKE", "TEACHER", entity.getTeacherId(),
                "撤销授权 " + entity.getScopeType() + ":" + entity.getScopeId());
    }

    private void requireAdmin() {
        if (!SecurityUtils.current().isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    private List<ScopeDtos.Resp> toRespList(List<TeacherScope> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> teacherNames = nameLookup.teacherNames(
                records.stream().map(TeacherScope::getTeacherId).toList());
        Map<Long, String> classNames = nameLookup.classNames(records.stream()
                .filter(s -> "CLASS".equals(s.getScopeType()))
                .map(TeacherScope::getScopeId).toList());
        Map<Long, String> courseNames = nameLookup.courseNames(records.stream()
                .filter(s -> "COURSE".equals(s.getScopeType()))
                .map(TeacherScope::getScopeId).toList());

        List<ScopeDtos.Resp> list = new ArrayList<>(records.size());
        for (TeacherScope s : records) {
            String scopeName = "CLASS".equals(s.getScopeType())
                    ? classNames.get(s.getScopeId())
                    : courseNames.get(s.getScopeId());
            list.add(new ScopeDtos.Resp(
                    s.getId(), s.getTeacherId(),
                    s.getTeacherId() == null ? null : teacherNames.get(s.getTeacherId()),
                    s.getScopeType(), s.getScopeId(), scopeName,
                    s.getGrantedBy(), s.getCreatedAt()));
        }
        return list;
    }
}
