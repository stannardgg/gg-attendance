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
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.domain.Course;
import com.fa.org.infra.CourseMapper;
import com.fa.org.infra.NameLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程 CRUD。
 *
 * <p>与班级的三处差异（都在 §5.3.1 里）：
 * ① code 是 2~32 位<b>大写</b>字母数字，且唯一；name 允许重名（不同学期可同名）；
 * ② 删除时没有「班内还有学生」这种前置校验——课程不承载学生；
 * ③ 同样「有会话引用就归档」。
 */
@Service
public class CourseService {

    private final CourseMapper courseMapper;
    private final JdbcTemplate jdbc;
    private final NameLookup nameLookup;
    private final AuditWriter audit;

    public CourseService(CourseMapper courseMapper, JdbcTemplate jdbc,
                         NameLookup nameLookup, AuditWriter audit) {
        this.courseMapper = courseMapper;
        this.jdbc = jdbc;
        this.nameLookup = nameLookup;
        this.audit = audit;
    }

    @DataScope
    public PageResult<CourseDtos.Resp> list(PageQuery query, String keyword, String semester, String status) {
        Set<Long> allowed = ScopeContext.current().courseFilter();
        if (allowed != null && allowed.isEmpty()) {
            return PageResult.empty(query);
        }

        var wrapper = Wrappers.<Course>lambdaQuery();
        if (allowed != null) {
            wrapper.in(Course::getId, allowed);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Course::getName, kw).or().like(Course::getCode, kw));
        }
        if (semester != null && !semester.isBlank()) {
            wrapper.eq(Course::getSemester, semester);
        }
        if (status == null || status.isBlank()) {
            wrapper.eq(Course::getStatus, "ACTIVE");
        } else if (!"ALL".equalsIgnoreCase(status)) {
            wrapper.eq(Course::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(Course::getId);

        IPage<Course> page = courseMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    @DataScope
    public CourseDtos.Resp get(long id) {
        ScopeContext.requireCourse(id);
        Course entity = courseMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public CourseDtos.Resp create(CourseDtos.CreateReq req) {
        Long dup = courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getCode, req.code()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CODE_DUPLICATED, "课程编码已存在");
        }

        Course entity = new Course();
        entity.setCode(req.code());
        entity.setName(req.name());
        entity.setSemester(req.semester());
        entity.setOwnerTeacherId(req.ownerTeacherId());
        entity.setRemark(req.remark());
        entity.setStatus("ACTIVE");
        courseMapper.insert(entity);

        audit.write("COURSE_CREATE", "COURSE", entity.getId(), "创建课程 " + entity.getName());
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public CourseDtos.Resp update(long id, CourseDtos.UpdateReq req) {
        Course entity = courseMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (req.name() != null) {
            entity.setName(req.name());
        }
        if (req.semester() != null) {
            entity.setSemester(req.semester());
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
        courseMapper.updateById(entity);
        audit.write("COURSE_UPDATE", "COURSE", id, "编辑课程 " + entity.getName());
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public ClassDtos.DeleteResult delete(long id) {
        Course entity = courseMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        Long n = jdbc.queryForObject(
                "select count(*) from checkin_session where course_id = ?", Long.class, id);
        long sessions = n == null ? 0 : n;

        if (sessions > 0) {
            entity.setStatus("ARCHIVED");
            courseMapper.updateById(entity);
            audit.write("COURSE_ARCHIVE", "COURSE", id, "因被 " + sessions + " 场会话引用而归档");
            return new ClassDtos.DeleteResult(id, true);
        }

        courseMapper.deleteById(id);
        audit.write("COURSE_DELETE", "COURSE", id, "删除课程 " + entity.getName());
        return new ClassDtos.DeleteResult(id, false);
    }

    /** 供签到模块校验课程可用性：必须存在且 ACTIVE。 */
    public Course requireActive(long courseId) {
        Course entity = courseMapper.selectById(courseId);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.ORG_ARCHIVED, "该课程已归档");
        }
        return entity;
    }

    private List<CourseDtos.Resp> toRespList(List<Course> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> teacherNames = nameLookup.teacherNames(
                records.stream().map(Course::getOwnerTeacherId).toList());
        List<CourseDtos.Resp> list = new ArrayList<>(records.size());
        for (Course c : records) {
            list.add(new CourseDtos.Resp(
                    c.getId(), c.getCode(), c.getName(), c.getSemester(),
                    c.getOwnerTeacherId(),
                    c.getOwnerTeacherId() == null ? null : teacherNames.get(c.getOwnerTeacherId()),
                    c.getStatus(), c.getRemark(), c.getCreatedAt(), c.getUpdatedAt()));
        }
        return list;
    }
}
