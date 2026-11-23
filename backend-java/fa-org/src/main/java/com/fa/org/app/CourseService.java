package com.fa.org.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.domain.Course;
import com.fa.org.domain.TeacherScope;
import com.fa.org.infra.CourseMapper;
import com.fa.org.infra.NameLookup;
import com.fa.org.infra.TeacherScopeMapper;
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
 *
 * <p>CR-027：开课教师不再是纯归属标注——管理员写入 {@code ownerTeacherId}
 * 时同步给该教师授予 COURSE 授权（幂等，等同教师管理「授权范围」的授予）；
 * TEACHER 身份建课强制 owner=本人、改课不允许动 owner（与 Agent
 * apply_owner_teacher 同口径）。
 */
@Service
public class CourseService {

    private final CourseMapper courseMapper;
    private final TeacherScopeMapper scopeMapper;
    private final JdbcTemplate jdbc;
    private final NameLookup nameLookup;
    private final AuditWriter audit;

    public CourseService(CourseMapper courseMapper, TeacherScopeMapper scopeMapper,
                         JdbcTemplate jdbc, NameLookup nameLookup, AuditWriter audit) {
        this.courseMapper = courseMapper;
        this.scopeMapper = scopeMapper;
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

        // CR-027：教师身份建课，开课教师强制为本人（管理员按请求原样）
        Long owner = req.ownerTeacherId();
        CurrentUser user = SecurityUtils.currentOrNull();
        if (user != null && user.isTeacher()) {
            owner = user.teacherId();
        }

        Course entity = new Course();
        entity.setCode(req.code());
        entity.setName(req.name());
        entity.setSemester(req.semester());
        entity.setOwnerTeacherId(owner);
        entity.setRemark(req.remark());
        entity.setStatus("ACTIVE");
        courseMapper.insert(entity);

        syncOwnerScope(entity.getId(), owner);

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
        // CR-027：只有管理员（及无身份的系统内部调用）写 owner 才同步授权；
        // TEACHER 编辑沿用 Agent 口径——owner 仍落为本人（apply_owner_teacher
        // 注入的就是本人），但只是归属标注，不产生授权（教师只能动授权范围
        // 内的课程，不构成新的授权来源）。null = 不改（部分更新口径）。
        Long syncedOwner = null;
        if (req.ownerTeacherId() != null) {
            CurrentUser actor = SecurityUtils.currentOrNull();
            entity.setOwnerTeacherId(req.ownerTeacherId());
            if (actor == null || !actor.isTeacher()) {
                syncedOwner = req.ownerTeacherId();
            }
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        if (req.remark() != null) {
            entity.setRemark(req.remark());
        }
        courseMapper.updateById(entity);
        if (syncedOwner != null) {
            syncOwnerScope(id, syncedOwner);
        }
        audit.write("COURSE_UPDATE", "COURSE", id, "编辑课程 " + entity.getName());
        return toRespList(List.of(entity)).get(0);
    }

    /**
     * CR-027：开课教师 → teacher_scope(COURSE) 同步授权。
     *
     * <p>幂等：已存在同 (teacherId, COURSE, courseId) 直接跳过，所以重复保存
     * 不会撞唯一约束。教师不存在直接拒绝——owner 现在驱动授权，允许野值会
     * 留下指向幽灵教师的授权行。
     */
    private void syncOwnerScope(long courseId, Long teacherId) {
        if (teacherId == null) {
            return;
        }
        Long n = jdbc.queryForObject(
                "select count(*) from teacher where id = ?", Long.class, teacherId);
        if (n == null || n == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "开课教师不存在");
        }
        Long dup = scopeMapper.selectCount(Wrappers.<TeacherScope>lambdaQuery()
                .eq(TeacherScope::getTeacherId, teacherId)
                .eq(TeacherScope::getScopeType, "COURSE")
                .eq(TeacherScope::getScopeId, courseId));
        if (dup != null && dup > 0) {
            return;
        }
        TeacherScope scope = new TeacherScope();
        scope.setTeacherId(teacherId);
        scope.setScopeType("COURSE");
        scope.setScopeId(courseId);
        CurrentUser user = SecurityUtils.currentOrNull();
        scope.setGrantedBy(user == null ? null : user.accountId());
        scopeMapper.insert(scope);
        audit.write("SCOPE_GRANT", "TEACHER", teacherId, "开课教师同步授权 COURSE:" + courseId);
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
        // CR-026：授权行跟着课程走，孤儿行会让快照 join 出幽灵学生
        jdbc.update("delete from course_student_grant where course_id = ?", id);
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
