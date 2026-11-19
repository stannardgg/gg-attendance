package com.fa.system.app;

import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.system.api.dto.SystemDtos;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 概览指标。
 *
 * <p>口径按契约原文：`(PRESENT + LATE) / 应到`，LEAVE/EXEMPT 进分母不进分子。
 * 分母取 `session_expected`（会话创建时冻结的应到名单），不是「当前在读人数」——
 * 转班会改在读人数，用它算历史出勤率会把昨天的数据也算错。
 *
 * <p>范围：教师只看授权班级（ScopeContext.classFilter）。空集直接返回全 0，
 * 而不是去查全表再过滤——少一次全表扫描，也避免漏过滤导致越权看数据。
 */
@Service
public class StatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @DataScope
    public SystemDtos.Overview overview(Long classId, Long courseId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        // trend 默认给近 7 天（契约原文），传了区间就按区间——但区间不影响上面四张卡片，
        // 那四张永远看「今天」，否则「今日出勤率」会变成区间均值。
        LocalDate start = from != null ? from : today.minusDays(6);
        LocalDate end = to != null ? to : today;

        Set<Long> allowed = ScopeContext.current().classFilter();
        if (allowed != null && allowed.isEmpty()) {
            return new SystemDtos.Overview(0, 0, 0, 0.0, 0, trend(7, classId, courseId, allowed));
        }

        Filter f = new Filter(classId, courseId, allowed);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();

        // 概览卡片按「今天」统计，不受 dateFrom/dateTo 影响：
        // 那两个参数只用于下钻趋势，混在一起会让「今日出勤率」显示成区间均值。
        int sessions = count("""
                SELECT COUNT(*) FROM checkin_session s WHERE s.start_at >= ? AND s.start_at < ?
                """ + f.sessionWhere(), args(dayStart, dayEnd, f, "s"));

        int expected = count("""
                SELECT COUNT(*) FROM session_expected se
                JOIN checkin_session s ON s.id = se.session_id
                WHERE s.start_at >= ? AND s.start_at < ?
                """ + f.sessionWhere(), args(dayStart, dayEnd, f, "s"));

        int present = count("""
                SELECT COUNT(*) FROM attendance a
                JOIN checkin_session s ON s.id = a.session_id
                WHERE s.start_at >= ? AND s.start_at < ? AND a.status IN ('PRESENT', 'LATE')
                """ + f.sessionWhere(), args(dayStart, dayEnd, f, "s"));

        return new SystemDtos.Overview(
                sessions, expected, present, rate(present, expected),
                pendingAppeals(f), trendBetween(start, end, f));
    }

    /** 近 n 天（含今天）的每日出勤率，日期升序，缺失的日期补 0 而不是不返回——前端横轴要等距。 */
    private List<SystemDtos.TrendPoint> trend(int days, Long classId, Long courseId, Set<Long> allowed) {
        if (allowed != null && allowed.isEmpty()) {
            return fillEmpty(LocalDate.now().minusDays(days - 1L), LocalDate.now());
        }
        return trendBetween(LocalDate.now().minusDays(days - 1L), LocalDate.now(),
                new Filter(classId, courseId, allowed));
    }

    private List<SystemDtos.TrendPoint> trendBetween(LocalDate from, LocalDate to, Filter f) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        Map<LocalDate, int[]> byDay = new HashMap<>();   // [expected, present]
        collect(byDay, 0, """
                SELECT DATE(s.start_at) d, COUNT(*) c FROM session_expected se
                JOIN checkin_session s ON s.id = se.session_id
                WHERE s.start_at >= ? AND s.start_at < ?
                """ + f.sessionWhere() + " GROUP BY d", args(start, end, f, "s"));

        collect(byDay, 1, """
                SELECT DATE(s.start_at) d, COUNT(*) c FROM attendance a
                JOIN checkin_session s ON s.id = a.session_id
                WHERE s.start_at >= ? AND s.start_at < ? AND a.status IN ('PRESENT', 'LATE')
                """ + f.sessionWhere() + " GROUP BY d", args(start, end, f, "s"));

        List<SystemDtos.TrendPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int[] v = byDay.getOrDefault(d, new int[]{0, 0});
            out.add(new SystemDtos.TrendPoint(d.format(DAY), rate(v[1], v[0]), v[0], v[1]));
        }
        return out;
    }

    private List<SystemDtos.TrendPoint> fillEmpty(LocalDate from, LocalDate to) {
        List<SystemDtos.TrendPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(new SystemDtos.TrendPoint(d.format(DAY), 0.0, 0, 0));
        }
        return out;
    }

    private void collect(Map<LocalDate, int[]> byDay, int slot, String sql, Object[] args) {
        jdbc.query(sql, rs -> {
            // 注意：这里是 RowCallbackHandler（游标已在当前行），不要再 while(rs.next())
            LocalDate d = rs.getDate("d").toLocalDate();
            byDay.computeIfAbsent(d, k -> new int[]{0, 0})[slot] = rs.getInt("c");
        }, args);
    }

    private int pendingAppeals(Filter f) {
        Set<Long> allowed = f.allowed;
        if (allowed == null) {
            return count("SELECT COUNT(*) FROM appeal WHERE status = 'PENDING'", new Object[0]);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM appeal ap
                JOIN attendance a ON a.id = ap.attendance_id
                JOIN checkin_session s ON s.id = a.session_id
                WHERE ap.status = 'PENDING' AND s.class_id IN (%s)
                """.formatted(placeholders(allowed.size())));
        return count(sql.toString(), allowed.toArray());
    }

    private int count(String sql, Object[] args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static double rate(int present, int expected) {
        if (expected <= 0) {
            return 0.0;
        }
        // 保留 4 位：前端展示百分比时要乘 100，多留两位避免 33.33 → 33.3 的舍入叠加
        return Math.round(present * 10000.0 / expected) / 10000.0;
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static Object[] args(LocalDateTime a, LocalDateTime b, Filter f, String alias) {
        List<Object> out = new ArrayList<>(List.of(a, b));
        if (f.allowed != null) {
            out.addAll(f.allowed);
        }
        if (f.courseId != null) {
            out.add(f.courseId);
        }
        if (f.classId != null) {
            out.add(f.classId);
        }
        return out.toArray();
    }

    /** 范围过滤条件；顺序必须与 {@link #args} 一致（allowed → courseId → classId）。 */
    private record Filter(Long classId, Long courseId, Set<Long> allowed) {
        String sessionWhere() {
            StringBuilder sb = new StringBuilder();
            if (allowed != null) {
                sb.append(" AND s.class_id IN (").append(placeholders(allowed.size())).append(")");
            }
            if (courseId != null) {
                sb.append(" AND s.course_id = ?");
            }
            if (classId != null) {
                sb.append(" AND s.class_id = ?");
            }
            return sb.toString();
        }
    }
}
