package com.fa.org.infra;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 批量取名工具。
 *
 * <p>为什么用 JdbcTemplate 直接读 teacher 表，而不是调 fa-auth 的服务：
 * fa-auth 已经依赖 fa-org（学生激活要用），反过来依赖会形成环。
 * 这里只读一个 name 字段，且只用于展示拼接，不参与任何业务判断——
 * 等 B4 引入统一的查询层时再替换掉。
 *
 * <p>刻意做成「批量」而不是「按 id 单查」：列表页 20 行，单查就是 20 次往返（N+1）。
 */
@Component
public class NameLookup {

    private final JdbcTemplate jdbc;

    public NameLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, String> teacherNames(Collection<Long> ids) {
        return names("select id, name from teacher where id in (%s)", ids);
    }

    public Map<Long, String> classNames(Collection<Long> ids) {
        return names("select id, name from class_group where id in (%s)", ids);
    }

    public Map<Long, String> courseNames(Collection<Long> ids) {
        return names("select id, name from course where id in (%s)", ids);
    }

    private Map<Long, String> names(String sqlTemplate, Collection<Long> ids) {
        List<Long> clean = ids == null ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            // 返回可变 Map 而不是 Map.of()：调用方会拿 null 键来查（如 ownerTeacherId 为空），
            // Map.of() 的 get(null) 会抛 NPE —— 这正是 B2 第一次冒烟就踩到的坑。
            return new HashMap<>();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(clean.size(), "?"));
        Map<Long, String> result = new HashMap<>();
        jdbc.query(String.format(sqlTemplate, placeholders),
                rs -> {
                    result.put(rs.getLong("id"), rs.getString("name"));
                },
                clean.toArray());
        return result;
    }
}
