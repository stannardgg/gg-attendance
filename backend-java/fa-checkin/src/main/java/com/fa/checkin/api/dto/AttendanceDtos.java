package com.fa.checkin.api.dto;

import java.time.LocalDateTime;

/** 管理端考勤视图（契约 java-api.yaml `Attendance`，字段名逐字对齐）。 */
public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    /** id 可能为 null：查单场时以应到名单为基表，未签到的人还没有 attendance 记录。 */
    public record Row(
            Long id,
            long sessionId,
            String sessionTitle,
            Long classId,
            String className,
            Long courseId,
            String courseName,
            long studentId,
            String studentNo,
            String studentName,
            String status,
            LocalDateTime checkinAt,
            Double similarity,
            Double livenessMedian,
            Double identitySim,
            Integer actionCount,
            String actionSeq,
            String rejectGate,
            String adjustReason,
            Long adjustedBy) {
    }

    /** 手动改考勤：必须带原因，且原因会跟着这条记录长期留存。 */
    public record PatchReq(String status, String adjustReason) {
    }
}
