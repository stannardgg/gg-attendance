package com.fa.agent.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 的能力级别开关。事实来源是 {@code sys_config}（§11.8：默认值只是兜底，实际以库为准）。
 *
 * <p>为什么带缓存：每轮对话都会拉一次 catalog，如果每次都查 4 行 sys_config，
 * 一个班 40 人同时用就会变成 160 次查询/轮。15 秒的缓存足够——
 * 关掉 L2 这种操作不需要「毫秒级生效」，需要的是「关掉之后确实不生效」。
 *
 * <p><b>缺 key 时的默认值</b>：L0/L1/L2 开、L3 关。这是文档 §2.2 D8 的默认表，
 * 写死在这里而不是靠库里必须有数据——空库也要能跑起来。
 */
@Component
public class AgentLevelService {

    private static final Logger log = LoggerFactory.getLogger(AgentLevelService.class);
    private static final long CACHE_MILLIS = 15_000L;

    private final JdbcTemplate jdbc;
    private final AtomicLong loadedAt = new AtomicLong(0);
    private volatile Map<String, Boolean> cached = defaults();

    public AgentLevelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Boolean> levels() {
        long now = System.currentTimeMillis();
        if (now - loadedAt.get() > CACHE_MILLIS) {
            cached = load();
            loadedAt.set(now);
        }
        return cached;
    }

    public boolean enabled(String level) {
        return Boolean.TRUE.equals(levels().get(level));
    }

    private Map<String, Boolean> load() {
        Map<String, Boolean> result = defaults();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'AGENT_LEVEL_%'");
            for (Map<String, Object> row : rows) {
                String key = String.valueOf(row.get("config_key"));
                String level = key.substring("AGENT_LEVEL_".length());
                if (result.containsKey(level)) {
                    result.put(level, "true".equalsIgnoreCase(String.valueOf(row.get("config_value"))));
                }
            }
        } catch (RuntimeException e) {
            // 表还没建好或库不可达时不要让 Agent 整体不可用：用默认值，打日志
            log.warn("agent-level-load-failed，本次使用默认级别", e);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Boolean> defaults() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("L0", true);
        m.put("L1", true);
        m.put("L2", true);
        m.put("L3", false);
        return m;
    }
}
