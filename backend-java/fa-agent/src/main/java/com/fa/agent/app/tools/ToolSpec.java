package com.fa.agent.app.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fa.agent.api.dto.AgentDtos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 工具的定义 + 三段处理逻辑。
 *
 * <p><b>为什么用「一个工具三段函数」而不是一个大 switch</b>：
 * 两阶段确认要求「预览」与「执行」是**两个独立的代码路径**——预览绝不能有副作用。
 * 把它们写成两个函数，diff 评审时能一眼看出预览函数里有没有偷偷写库；
 * 写在一个方法里靠 if 分支，早晚会有人把写操作提到 if 之前。
 *
 * <p>L0/L1 用 {@link #read}，L2 用 {@link #plan} + {@link #execute} + {@link #undo}。
 */
public record ToolSpec(String name,
                       String level,
                       String title,
                       String description,
                       boolean undoable,
                       String undoNote,
                       Map<String, Object> paramsSchema,
                       Map<String, Object> returnsSchema,
                       ReadFn read,
                       PlanFn plan,
                       ExecFn exec,
                       UndoFn undo) {

    @FunctionalInterface
    public interface ReadFn {
        AgentDtos.ToolResult read(ToolCtx ctx, Map<String, Object> params);
    }

    @FunctionalInterface
    public interface PlanFn {
        Plan plan(ToolCtx ctx, Map<String, Object> params);
    }

    @FunctionalInterface
    public interface ExecFn {
        AgentDtos.ToolResult exec(ToolCtx ctx, Map<String, Object> params);
    }

    @FunctionalInterface
    /**
     * 撤销动作。
     *
     * <p>为什么同时给 before 和 after：新建类工具（create_class / create_course /
     * create_session）的主键是执行时才生成的，params 里根本没有——只按 params 取值会 NPE。
     * after 就是当时 exec 返回的结果，撤销时原样读回，才能定位到「刚建出来的那一条」。
     * 修改类工具用 before 做字段回滚，新建类工具用 after 定位资源，各取所需。
     */
    public interface UndoFn {
        void undo(ToolCtx ctx, Map<String, Object> params, JsonNode before, JsonNode after);
    }

    /**
     * 预览结果。
     *
     * @param preview 给确认卡渲染的全部内容
     * @param before  执行前的快照，落进 agent_action_log.before_json，撤销时原样读回
     */
    public record Plan(AgentDtos.Preview preview, Object before) {
    }

    public boolean requiresConfirm() {
        return "L2".equals(level) || "L3".equals(level);
    }

    public static Builder builder(String name, String level, String title) {
        return new Builder(name, level, title);
    }

    /** 小builder，只为了让 20 多个工具的定义读起来像配置而不是嵌套构造器。 */
    public static final class Builder {
        private final String name;
        private final String level;
        private final String title;
        private String description;
        private boolean undoable;
        private String undoNote;
        private Map<String, Object> paramsSchema = Map.of("type", "object", "additionalProperties", false);
        private Map<String, Object> returnsSchema = Map.of("type", "object");
        private ReadFn read;
        private PlanFn plan;
        private ExecFn exec;
        private UndoFn undo;

        private Builder(String name, String level, String title) {
            this.name = name;
            this.level = level;
            this.title = title;
            this.description = title;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder undoable(String note) {
            this.undoable = true;
            this.undoNote = note;
            return this;
        }

        public Builder params(Map<String, Object> schema) {
            this.paramsSchema = schema;
            return this;
        }

        public Builder returns(Map<String, Object> schema) {
            this.returnsSchema = schema;
            return this;
        }

        public Builder read(ReadFn fn) {
            this.read = fn;
            return this;
        }

        public Builder plan(PlanFn fn) {
            this.plan = fn;
            return this;
        }

        public Builder exec(ExecFn fn) {
            this.exec = fn;
            return this;
        }

        public Builder undo(UndoFn fn) {
            this.undo = fn;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(name, level, title, description, undoable, undoNote,
                    paramsSchema, returnsSchema, read, plan, exec, undo);
        }
    }

    // ---------------- JSON Schema 小工具 ----------------

    /** 构造 object schema：properties + required + additionalProperties=false（契约默认）。 */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> int64(String description) {
        return Map.of("type", "integer", "format", "int64", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> date(String description) {
        return Map.of("type", "string", "format", "date", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> enums(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }
}
