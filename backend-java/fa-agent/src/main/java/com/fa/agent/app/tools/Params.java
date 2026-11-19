package com.fa.agent.app.tools;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具入参校验 + 取值。Schema 由 catalog 下发，这里做**服务端二次校验**。
 *
 * <p>为什么不能只靠 Agent 侧按 schema 校验：Agent 是被编排的模型，输出不可信。
 * 三个必须拦住的典型输入：
 * <ul>
 *   <li>未知字段 —— 例如用户诱导模型把 {@code confirmToken} 塞进 params。
 *       契约里所有工具都是 {@code additionalProperties:false}，多一个键就必须拒。</li>
 *   <li>类型错位 —— JSON 的 {@code "3"} 与 {@code 3} 在 Java 里一个 String 一个 Integer，
 *       直接强转会 ClassCastException 变成 500。</li>
 *   <li>枚举越界 —— 例如 status 传 {@code DONE}，落到 SQL 里就是一条脏数据。</li>
 * </ul>
 *
 * <p>错误码复用 FA-2001（契约 2xxx 号段没有通用校验码，见 WP-02 已记录的软点）。
 */
public final class Params {

    private final Map<String, Object> raw;
    private final Map<String, Object> schema;

    private Params(Map<String, Object> raw, Map<String, Object> schema) {
        this.raw = raw;
        this.schema = schema;
    }

    @SuppressWarnings("unchecked")
    public static Params of(Map<String, Object> raw, Map<String, Object> schema) {
        Map<String, Object> safe = raw == null ? Map.of() : raw;
        Params p = new Params(safe, schema);
        p.validate();
        return p;
    }

    private Map<String, Object> properties() {
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            return typed;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private void validate() {
        Map<String, Object> props = properties();
        for (String key : raw.keySet()) {
            if (!props.containsKey(key)) {
                throw invalid("未知参数 " + key + "（该工具不接受此字段）");
            }
        }
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            for (Object r : list) {
                String key = String.valueOf(r);
                if (raw.get(key) == null) {
                    throw invalid("缺少必填参数 " + key);
                }
            }
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object rule = props.get(e.getKey());
            if (rule instanceof Map<?, ?> ruleMap && e.getValue() != null) {
                checkRule(e.getKey(), e.getValue(), (Map<String, Object>) ruleMap);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkRule(String key, Object value, Map<String, Object> rule) {
        Object enumRule = rule.get("enum");
        if (enumRule instanceof List<?> allowed && !allowed.contains(String.valueOf(value))) {
            throw invalid("参数 " + key + " 只能取 " + allowed);
        }
        Object maxLength = rule.get("maxLength");
        if (maxLength instanceof Number n && String.valueOf(value).length() > n.intValue()) {
            throw invalid("参数 " + key + " 超过最大长度 " + n);
        }
        Object pattern = rule.get("pattern");
        if (pattern != null && !Pattern.compile(String.valueOf(pattern))
                .matcher(String.valueOf(value)).matches()) {
            throw invalid("参数 " + key + " 格式不正确");
        }
        Object min = rule.get("minimum");
        if (min instanceof Number n && toDouble(key, value) < n.doubleValue()) {
            throw invalid("参数 " + key + " 不能小于 " + n);
        }
        Object max = rule.get("maximum");
        if (max instanceof Number n && toDouble(key, value) > n.doubleValue()) {
            throw invalid("参数 " + key + " 不能大于 " + n);
        }
    }

    private static double toDouble(String key, Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw invalid("参数 " + key + " 必须是数字");
        }
    }

    // ---------------- 取值 ----------------

    public String str(String key) {
        Object v = raw.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    public String requireStr(String key) {
        String v = str(key);
        if (v == null || v.isEmpty()) {
            throw invalid("缺少必填参数 " + key);
        }
        return v;
    }

    public Long lng(String key) {
        Object v = raw.get(key);
        if (v == null) {
            return null;
        }
        return asLong(key, v);
    }

    public Long requireLng(String key) {
        Long v = lng(key);
        if (v == null) {
            throw invalid("缺少必填参数 " + key);
        }
        return v;
    }

    private static Long asLong(String key, Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw invalid("参数 " + key + " 必须是整数");
        }
    }

    public Integer integer(String key, Integer defaultValue) {
        Long v = lng(key);
        return v == null ? defaultValue : v.intValue();
    }

    public Boolean bool(String key, Boolean defaultValue) {
        Object v = raw.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public LocalDate date(String key) {
        String v = str(key);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw invalid("参数 " + key + " 必须是 yyyy-MM-dd");
        }
    }

    public List<Long> longList(String key) {
        Object v = raw.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<Long> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(asLong(key, o));
            }
            return out;
        }
        return List.of(asLong(key, v));
    }

    public Set<String> keys() {
        return new LinkedHashSet<>(raw.keySet());
    }

    public Map<String, Object> raw() {
        return raw;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }
}
