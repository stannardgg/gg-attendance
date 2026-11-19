package com.fa.org.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.domain.ClassGroup;
import com.fa.org.infra.ClassGroupMapper;
import com.fa.org.infra.NameLookup;
import com.fa.org.infra.StudentMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 班级 CRUD。
 *
 * <p>三条硬规则来自项目文档 §5.3.1：
 * ① code 创建后不可改（学生已用它激活）；
 * ② 班内仍有 ACTIVE 学生时不许删除，返回 FA-2005 且带 studentCount；
 * ③ 已被签到会话引用则只归档，不物理删除（历史考勤要能查）。
 *
 * <p>★ 空权限集合等于「什么都看不到」，不是「什么都看得到」。
 * 这是本类里最容易写错的一行——`if (allowed != null && !allowed.isEmpty())` 少了第二个条件
 * 就会变成越权。测试里专门有一个用例盯这个。
 */
@Service
public class ClassService {

    private final ClassGroupMapper classMapper;
    private final StudentMapper studentMapper;
    private final JdbcTemplate jdbc;
    private final NameLookup nameLookup;
    private final AuditWriter audit;

    public ClassService(ClassGroupMapper classMapper, StudentMapper studentMapper,
                        JdbcTemplate jdbc, NameLookup nameLookup, AuditWriter audit) {
        this.classMapper = classMapper;
        this.studentMapper = studentMapper;
        this.jdbc = jdbc;
        this.nameLookup = nameLookup;
        this.audit = audit;
    }

    @DataScope
    public PageResult<ClassDtos.Resp> list(PageQuery query, String keyword, String grade, String status) {
        Set<Long> allowed = ScopeContext.current().classFilter();
        if (allowed != null && allowed.isEmpty()) {
            return PageResult.empty(query);
        }

        var wrapper = Wrappers.<ClassGroup>lambdaQuery();
        if (allowed != null) {
            wrapper.in(ClassGroup::getId, allowed);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(ClassGroup::getName, kw).or().like(ClassGroup::getCode, kw));
        }
        if (grade != null && !grade.isBlank()) {
            wrapper.eq(ClassGroup::getGrade, grade);
        }
        if (status == null || status.isBlank()) {
            wrapper.eq(ClassGroup::getStatus, "ACTIVE");
        } else if (!"ALL".equalsIgnoreCase(status)) {
            wrapper.eq(ClassGroup::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(ClassGroup::getId);

        IPage<ClassGroup> page = classMapper.selectPage(
                new Page<>(query.page(), query.size()), wrapper);

        List<ClassDtos.Resp> items = toRespList(page.getRecords());
        return PageResult.of(items, page.getTotal(), query);
    }

    @DataScope
    public ClassDtos.Resp get(long id) {
        ScopeContext.requireClass(id);
        ClassGroup entity = classMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public ClassDtos.Resp create(ClassDtos.CreateReq req) {
        Long codeCount = classMapper.selectCount(
                Wrappers.<ClassGroup>lambdaQuery().eq(ClassGroup::getCode, req.code()));
        if (codeCount != null && codeCount > 0) {
            throw new BizException(ErrorCode.CLASS_DUPLICATED, "班级码已被占用");
        }
        Long nameCount = classMapper.selectCount(
                Wrappers.<ClassGroup>lambdaQuery().eq(ClassGroup::getName, req.name()));
        if (nameCount != null && nameCount > 0) {
            throw new BizException(ErrorCode.CLASS_DUPLICATED, "班级名称已存在");
        }

        ClassGroup entity = new ClassGroup();
        entity.setCode(req.code());
        entity.setName(req.name());
        entity.setGrade(req.grade());
        entity.setMajor(req.major());
        entity.setOwnerTeacherId(req.ownerTeacherId());
        entity.setRemark(req.remark());
        entity.setStatus("ACTIVE");
        classMapper.insert(entity);

        audit.write("CLASS_CREATE", "CLASS", entity.getId(), "创建班级 " + entity.getName());
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public ClassDtos.Resp update(long id, ClassDtos.UpdateReq req) {
        ClassGroup entity = classMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
        if (req.name() != null && !req.name().equals(entity.getName())) {
            Long dup = classMapper.selectCount(Wrappers.<ClassGroup>lambdaQuery()
                    .eq(ClassGroup::getName, req.name())
                    .ne(ClassGroup::getId, id));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.CLASS_DUPLICATED, "班级名称已存在");
            }
            entity.setName(req.name());
        }
        if (req.grade() != null) {
            entity.setGrade(req.grade());
        }
        if (req.major() != null) {
            entity.setMajor(req.major());
        }
        if (req.ownerTeacherId() != null) {
            entity.setOwnerTeacherId(req.ownerTeacherId());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        if (req.remark() != null) {
            entity.setRemark(req.remark());
        }
        classMapper.updateById(entity);
        audit.write("CLASS_UPDATE", "CLASS", id, "编辑班级 " + entity.getName());
        return toRespList(List.of(entity)).get(0);
    }

    /**
     * 删除或归档。
     *
     * <p>顺序很重要：先查在读学生，再查会话引用。反过来会先归档再报错，
     * 用户看到「不能删」但班级状态已经变了。
     */
    @Transactional
    public ClassDtos.DeleteResult delete(long id) {
        ClassGroup entity = classMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }

        long activeStudents = countActiveStudents(id);
        if (activeStudents > 0) {
            throw new BizException(ErrorCode.CLASS_HAS_STUDENTS,
                    "该班级下仍有 " + activeStudents + " 名在读学生，请先转出或归档",
                    Map.of("studentCount", activeStudents));
        }

        long sessions = countSessions(id);
        if (sessions > 0) {
            entity.setStatus("ARCHIVED");
            classMapper.updateById(entity);
            audit.write("CLASS_ARCHIVE", "CLASS", id, "因被 " + sessions + " 场会话引用而归档");
            return new ClassDtos.DeleteResult(id, true);
        }

        classMapper.deleteById(id);
        audit.write("CLASS_DELETE", "CLASS", id, "删除班级 " + entity.getName());
        return new ClassDtos.DeleteResult(id, false);
    }

    /** 供学生模块校验班级可用性：必须存在且 ACTIVE。 */
    public ClassGroup requireActive(long classId) {
        ClassGroup entity = classMapper.selectById(classId);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.ORG_ARCHIVED, "该班级已归档");
        }
        return entity;
    }

    public long countActiveStudents(long classId) {
        Long n = studentMapper.selectCount(Wrappers.<com.fa.org.domain.Student>lambdaQuery()
                .eq(com.fa.org.domain.Student::getClassId, classId)
                .eq(com.fa.org.domain.Student::getStatus, "ACTIVE"));
        return n == null ? 0 : n;
    }

    private long countSessions(long classId) {
        Long n = jdbc.queryForObject(
                "select count(*) from checkin_session where class_id = ?", Long.class, classId);
        return n == null ? 0 : n;
    }

    private List<ClassDtos.Resp> toRespList(List<ClassGroup> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = countActiveStudentsBatch(
                records.stream().map(ClassGroup::getId).toList());
        Map<Long, String> teacherNames = nameLookup.teacherNames(
                records.stream().map(ClassGroup::getOwnerTeacherId).toList());

        List<ClassDtos.Resp> list = new ArrayList<>(records.size());
        for (ClassGroup c : records) {
            list.add(new ClassDtos.Resp(
                    c.getId(), c.getCode(), c.getName(), c.getGrade(), c.getMajor(),
                    c.getOwnerTeacherId(),
                    c.getOwnerTeacherId() == null ? null : teacherNames.get(c.getOwnerTeacherId()),
                    c.getStatus(), c.getRemark(), counts.getOrDefault(c.getId(), 0L),
                    c.getCreatedAt(), c.getUpdatedAt()));
        }
        return list;
    }

    private Map<Long, Long> countActiveStudentsBatch(Collection<Long> classIds) {
        List<Long> ids = classIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        Map<Long, Long> result = new HashMap<>();
        jdbc.query(
                "select class_id, count(*) as c from student "
                        + "where status = 'ACTIVE' and class_id in (" + placeholders + ") group by class_id",
                rs -> {
                    result.put(rs.getLong("class_id"), rs.getLong("c"));
                },
                ids.toArray());
        return result;
    }
}
