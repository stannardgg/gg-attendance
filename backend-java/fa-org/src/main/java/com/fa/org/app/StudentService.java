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
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.api.dto.StudentDtos;
import com.fa.org.domain.ClassGroup;
import com.fa.org.domain.FaceProfile;
import com.fa.org.domain.Student;
import com.fa.org.infra.ClassGroupMapper;
import com.fa.org.infra.FaceProfileMapper;
import com.fa.org.infra.NameLookup;
import com.fa.org.infra.StudentMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学生 CRUD、转班与 CSV 批量导入。
 *
 * <p>导入的取舍（§14.3 验收项）：<b>逐行校验、分批提交、已成功的不回滚</b>。
 * 1000 行里有 5 行非法，预期是 995 成功 + 5 行报行号。
 * 如果整批放在一个事务里，任何一行失败就全废——而教师面对 1000 行数据时
 * 最怕的就是「改完一个错再传一次，永远传不完」。
 *
 * <p>所以 importCsv <b>没有</b> {@code @Transactional}：每批独立提交。
 */
@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);
    private static final int BATCH_SIZE = 100;

    private final StudentMapper studentMapper;
    private final ClassGroupMapper classMapper;
    private final FaceProfileMapper faceMapper;
    private final NameLookup nameLookup;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;

    public StudentService(StudentMapper studentMapper, ClassGroupMapper classMapper,
                          FaceProfileMapper faceMapper, NameLookup nameLookup,
                          AuditWriter audit, JdbcTemplate jdbc) {
        this.studentMapper = studentMapper;
        this.classMapper = classMapper;
        this.faceMapper = faceMapper;
        this.nameLookup = nameLookup;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @DataScope
    public PageResult<StudentDtos.Resp> list(PageQuery query, Long classId, String keyword, String status) {
        Set<Long> allowed = ScopeContext.current().classFilter();
        if (allowed != null && allowed.isEmpty()) {
            return PageResult.empty(query);
        }
        if (classId != null) {
            // 指定了班级就必须在授权范围内，否则直接拒（而不是返回空）
            ScopeContext.requireClass(classId);
        }

        var wrapper = Wrappers.<Student>lambdaQuery();
        if (classId != null) {
            wrapper.eq(Student::getClassId, classId);
        } else if (allowed != null) {
            wrapper.in(Student::getClassId, allowed);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Student::getName, kw).or().like(Student::getStudentNo, kw));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Student::getStatus, status);
        }
        wrapper.orderByAsc(Student::getStudentNo);

        IPage<Student> page = studentMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    @Transactional
    public StudentDtos.Resp create(StudentDtos.CreateReq req) {
        ClassGroup clazz = classMapper.selectById(req.classId());
        if (clazz == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
        if (!"ACTIVE".equals(clazz.getStatus())) {
            throw new BizException(ErrorCode.ORG_ARCHIVED, "该班级已归档");
        }
        Long dup = studentMapper.selectCount(
                Wrappers.<Student>lambdaQuery().eq(Student::getStudentNo, req.studentNo()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CODE_DUPLICATED, "学号已存在");
        }

        Student entity = new Student();
        entity.setStudentNo(req.studentNo());
        entity.setName(req.name());
        entity.setClassId(req.classId());
        entity.setStatus("ACTIVE");
        studentMapper.insert(entity);

        faceMapper.insert(newFaceProfile(entity.getId()));
        audit.write("STUDENT_CREATE", "STUDENT", entity.getId(), "新增学生 " + entity.getStudentNo());
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public StudentDtos.Resp update(long id, StudentDtos.UpdateReq req) {
        Student entity = studentMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }

        if (req.name() != null) {
            entity.setName(req.name());
        }
        // 转班：改 class_id + 写审计（§5.3.1）
        if (req.classId() != null && !req.classId().equals(entity.getClassId())) {
            ClassGroup target = classMapper.selectById(req.classId());
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "目标班级不存在");
            }
            if (!"ACTIVE".equals(target.getStatus())) {
                throw new BizException(ErrorCode.ORG_ARCHIVED, "目标班级已归档");
            }
            Long from = entity.getClassId();
            entity.setClassId(req.classId());
            audit.write("STUDENT_TRANSFER", "STUDENT", id,
                    "转班：" + from + " → " + req.classId() + "（" + entity.getStudentNo() + "）");
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        studentMapper.updateById(entity);
        return toRespList(List.of(entity)).get(0);
    }

    @Transactional
    public ClassDtos.DeleteResult delete(long id) {
        Student entity = studentMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        Long attendance = jdbc.queryForObject(
                "select count(*) from attendance where student_id = ?", Long.class, id);
        if (attendance != null && attendance > 0) {
            entity.setStatus("ARCHIVED");
            studentMapper.updateById(entity);
            audit.write("STUDENT_ARCHIVE", "STUDENT", id, "有考勤记录，改为归档");
            return new ClassDtos.DeleteResult(id, true);
        }
        studentMapper.deleteById(id);
        jdbc.update("delete from face_profile where student_id = ?", id);
        // 向量也要清：否则下一次快照拉取仍会把它发给 Python，被删学生还能被认出来
        jdbc.update("delete from face_vector where student_id = ?", id);
        // CR-026：课程授权也要清，否则发起签到时快照会并进一个已不存在的学生
        jdbc.update("delete from course_student_grant where student_id = ?", id);
        audit.write("STUDENT_DELETE", "STUDENT", id, "删除学生 " + entity.getStudentNo());
        return new ClassDtos.DeleteResult(id, false);
    }

    /**
     * CSV 批量导入。表头固定 {@code studentNo,name}（可选 {@code status}）。
     *
     * @param classCode 班级邀请码，用于定位班级；不信任 CSV 内的班级列
     */
    public StudentDtos.ImportResult importCsv(InputStream in, String classCode) {
        ClassGroup clazz = classMapper.selectOne(
                Wrappers.<ClassGroup>lambdaQuery().eq(ClassGroup::getCode, classCode));
        if (clazz == null) {
            // 与激活接口保持同一套模糊提示：不暴露「班级码不存在」
            throw new BizException(ErrorCode.NOT_FOUND, "班级码无效");
        }
        if (!"ACTIVE".equals(clazz.getStatus())) {
            throw new BizException(ErrorCode.ORG_ARCHIVED, "该班级已归档");
        }

        List<StudentDtos.ImportRowError> errors = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        int total = 0;
        int created = 0;
        int updated = 0;
        int failed = 0;
        List<Student> pending = new ArrayList<>();
        int line = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true)
                    .build();
            try (CSVParser parser = CSVParser.parse(reader, format)) {
                for (CSVRecord record : parser) {
                    // 行号从 1 起且把表头算进去，教师对着 Excel 行号能找到人
                    line = (int) record.getRecordNumber() + 1;
                    total++;

                    String studentNo = safeGet(record, "studentNo");
                    String name = safeGet(record, "name");

                    String bad = validate(studentNo, name, seenInFile);
                    if (bad != null) {
                        errors.add(new StudentDtos.ImportRowError(line, studentNo, bad));
                        failed++;
                        continue;
                    }
                    seenInFile.add(studentNo);

                    Student existing = studentMapper.selectOne(
                            Wrappers.<Student>lambdaQuery().eq(Student::getStudentNo, studentNo));
                    if (existing != null) {
                        existing.setName(name);
                        existing.setClassId(clazz.getId());
                        existing.setStatus("ACTIVE");
                        studentMapper.updateById(existing);
                        updated++;
                    } else {
                        Student s = new Student();
                        s.setStudentNo(studentNo);
                        s.setName(name);
                        s.setClassId(clazz.getId());
                        s.setStatus("ACTIVE");
                        pending.add(s);
                    }

                    if (pending.size() >= BATCH_SIZE) {
                        created += flush(pending);
                    }
                }
            }
            created += flush(pending);
        } catch (IOException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "CSV 读取失败：" + e.getMessage());
        }

        audit.write("STUDENT_IMPORT", "CLASS", clazz.getId(),
                "导入学生：新增 " + created + "，更新 " + updated + "，失败 " + failed);
        log.info("student-import class={} total={} created={} updated={} failed={}",
                clazz.getId(), total, created, updated, failed);
        return new StudentDtos.ImportResult(total, created, updated, failed, errors);
    }

    /** @return null 表示通过，否则返回失败原因 */
    static String validate(String studentNo, String name, Set<String> seen) {
        if (studentNo == null || studentNo.isBlank()) {
            return "学号为空";
        }
        if (studentNo.length() > 32) {
            return "学号超过 32 位";
        }
        if (name == null || name.isBlank()) {
            return "姓名为空";
        }
        if (name.length() > 64) {
            return "姓名超过 64 位";
        }
        if (seen.contains(studentNo)) {
            return "学号在文件内重复";
        }
        return null;
    }

    /** 一批提交，返回实际写入条数。发号器由数据库自增，逐条 insert 即可。 */
    private int flush(List<Student> pending) {
        int n = 0;
        for (Student s : pending) {
            studentMapper.insert(s);
            faceMapper.insert(newFaceProfile(s.getId()));
            n++;
        }
        pending.clear();
        return n;
    }

    public ScopeDtos.FaceStatusResp reviewFace(long studentId, ScopeDtos.FaceReviewReq req) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        FaceProfile profile = faceMapper.selectOne(
                Wrappers.<FaceProfile>lambdaQuery().eq(FaceProfile::getStudentId, studentId));
        if (profile == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该学生尚未提交人脸采集");
        }
        if (Boolean.TRUE.equals(req.approved())) {
            profile.setStatus("ACTIVE");
            profile.setRejectReason(null);
        } else {
            if (req.rejectReason() == null || req.rejectReason().isBlank()) {
                throw new BizException(ErrorCode.NOT_FOUND, "驳回时必须填写原因");
            }
            profile.setStatus("REJECTED");
            profile.setRejectReason(req.rejectReason());
        }
        profile.setReviewedAt(LocalDateTime.now());
        var reviewer = com.fa.common.security.SecurityUtils.currentOrNull();
        profile.setReviewedBy(reviewer == null ? null : reviewer.accountId());
        faceMapper.updateById(profile);

        audit.write("FACE_REVIEW", "STUDENT", studentId,
                "人脸审核：" + profile.getStatus());
        return new ScopeDtos.FaceStatusResp(profile.getStudentId(), profile.getStatus(),
                profile.getSampleCount(), profile.getSubmittedAt(), profile.getReviewedAt(),
                profile.getRejectReason());
    }

    private FaceProfile newFaceProfile(long studentId) {
        FaceProfile p = new FaceProfile();
        p.setStudentId(studentId);
        p.setStatus("NONE");
        p.setSampleCount(0);
        return p;
    }

    private static String safeGet(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column) : null;
    }

    private List<StudentDtos.Resp> toRespList(List<Student> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> classNames = nameLookup.classNames(
                records.stream().map(Student::getClassId).toList());

        Map<Long, String> faceStatus = new HashMap<>();
        List<Long> ids = records.stream().map(Student::getId).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("select student_id, status from face_profile where student_id in (" + placeholders + ")",
                rs -> {
                    faceStatus.put(rs.getLong("student_id"), rs.getString("status"));
                },
                ids.toArray());

        List<StudentDtos.Resp> list = new ArrayList<>(records.size());
        for (Student s : records) {
            list.add(new StudentDtos.Resp(
                    s.getId(), s.getStudentNo(), s.getName(), s.getClassId(),
                    s.getClassId() == null ? null : classNames.get(s.getClassId()), s.getStatus(),
                    s.getAccountId() != null,
                    faceStatus.getOrDefault(s.getId(), "NONE"),
                    s.getCreatedAt()));
        }
        return list;
    }
}
