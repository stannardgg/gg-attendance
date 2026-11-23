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
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.domain.Course;
import com.fa.org.domain.CourseGrant;
import com.fa.org.domain.Student;
import com.fa.org.infra.CourseGrantMapper;
import com.fa.org.infra.CourseMapper;
import com.fa.org.infra.StudentMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程-学生授权（CR-026）：把课程单独授权给指定学生，发起签到时并入应到快照。
 *
 * <p>这是<b>授权表</b>不是选课表：班级名单仍是默认应到，授权学生是追加。
 * 权限模型：
 * <ul>
 *   <li>课程侧：{@code ScopeContext.requireCourse}——教师只能动自己授权范围内的课程；</li>
 *   <li>学生侧：教师授权/撤销时，目标学生的<b>当前班级</b>必须也在其班级授权范围内
 *       （授权学生本质是动别人班的学生，不能越过班级可见性）；管理员不受限。</li>
 * </ul>
 *
 * <p>撤销 = 物理删除行；授权变动只影响之后新发起的会话，已存续会话不追溯（CR-001）。
 */
@Service
public class CourseGrantService {

    private final CourseGrantMapper grantMapper;
    private final CourseMapper courseMapper;
    private final StudentMapper studentMapper;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    public CourseGrantService(CourseGrantMapper grantMapper, CourseMapper courseMapper,
                              StudentMapper studentMapper, JdbcTemplate jdbc, AuditWriter audit) {
        this.grantMapper = grantMapper;
        this.courseMapper = courseMapper;
        this.studentMapper = studentMapper;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @DataScope
    public PageResult<CourseDtos.GrantResp> list(long courseId, PageQuery query) {
        ScopeContext.requireCourse(courseId);
        requireCourse(courseId);

        IPage<CourseGrant> page = grantMapper.selectPage(
                new Page<>(query.page(), query.size()),
                Wrappers.<CourseGrant>lambdaQuery()
                        .eq(CourseGrant::getCourseId, courseId)
                        .orderByDesc(CourseGrant::getId));

        List<CourseGrant> records = page.getRecords();
        List<CourseDtos.GrantResp> items = records.isEmpty()
                ? List.of()
                : toRespList(records);
        return PageResult.of(items, page.getTotal(), query);
    }

    /** 学生姓名/学号/班级：一条 join 查齐，避免行数 × 3 的 N+1。 */
    private List<CourseDtos.GrantResp> toRespList(List<CourseGrant> records) {
        String placeholders = String.join(",", java.util.Collections.nCopies(records.size(), "?"));
        List<Object> ids = new ArrayList<>();
        records.forEach(g -> ids.add(g.getStudentId()));
        Map<Long, CourseDtos.GrantResp> byStudent = new HashMap<>();
        jdbc.query("""
                select s.id, s.name, s.student_no, s.class_id, c.name as class_name
                from student s left join class_group c on c.id = s.class_id
                where s.id in (%s)
                """.formatted(placeholders),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> byStudent.put(
                        rs.getLong("id"), new CourseDtos.GrantResp(
                                null, null, rs.getLong("id"),
                                rs.getString("name"), rs.getString("student_no"),
                                (Long) rs.getObject("class_id"), rs.getString("class_name"),
                                null, null)),
                ids.toArray());

        List<CourseDtos.GrantResp> list = new ArrayList<>(records.size());
        for (CourseGrant g : records) {
            CourseDtos.GrantResp base = byStudent.get(g.getStudentId());
            list.add(new CourseDtos.GrantResp(
                    g.getId(), g.getCourseId(), g.getStudentId(),
                    base == null ? null : base.studentName(),
                    base == null ? null : base.studentNo(),
                    base == null ? null : base.classId(),
                    base == null ? null : base.className(),
                    g.getGrantedBy(), g.getCreatedAt()));
        }
        return list;
    }

    @Transactional
    @DataScope
    public CourseDtos.GrantResp grant(long courseId, CourseDtos.GrantReq req) {
        ScopeContext.requireCourse(courseId);
        Course course = requireCourse(courseId);

        Student student = studentMapper.selectById(req.studentId());
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        // 授权的是「在读学生」：已归档/转出痕迹的学生没有签到资格
        if (!"ACTIVE".equals(student.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "该学生不在读，无法授权");
        }
        // 教师不能越过班级可见性去动别的班的学生（管理员 admin=true 自动通过）
        ScopeContext.requireClass(student.getClassId());

        Long dup = grantMapper.selectCount(Wrappers.<CourseGrant>lambdaQuery()
                .eq(CourseGrant::getCourseId, courseId)
                .eq(CourseGrant::getStudentId, req.studentId()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.GRANT_DUPLICATED, "该学生已被授权此课程");
        }

        CourseGrant g = new CourseGrant();
        g.setCourseId(courseId);
        g.setStudentId(req.studentId());
        g.setGrantedBy(SecurityUtils.current().accountId());
        grantMapper.insert(g);

        audit.write("COURSE_GRANT", "COURSE", courseId,
                "授权学生 " + student.getName() + "（" + student.getStudentNo() + "）课程 " + course.getName());
        return toRespList(List.of(g)).get(0);
    }

    @Transactional
    @DataScope
    public void revoke(long courseId, long studentId) {
        ScopeContext.requireCourse(courseId);
        Course course = requireCourse(courseId);

        CourseGrant g = grantMapper.selectOne(Wrappers.<CourseGrant>lambdaQuery()
                .eq(CourseGrant::getCourseId, courseId)
                .eq(CourseGrant::getStudentId, studentId));
        if (g == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该学生未被授权此课程");
        }
        // 撤销同样受班级可见性约束：能授才能撤，口径一致
        Student student = studentMapper.selectById(studentId);
        if (student != null) {
            ScopeContext.requireClass(student.getClassId());
        }

        grantMapper.deleteById(g.getId());
        audit.write("COURSE_GRANT_REVOKE", "COURSE", courseId,
                "撤销授权：" + (student == null ? "studentId=" + studentId
                        : "学生 " + student.getName() + "（" + student.getStudentNo() + "）")
                        + " · 课程 " + course.getName());
    }

    private Course requireCourse(long courseId) {
        Course entity = courseMapper.selectById(courseId);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        return entity;
    }
}
