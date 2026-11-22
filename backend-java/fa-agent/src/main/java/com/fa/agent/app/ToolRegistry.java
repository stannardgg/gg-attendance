package com.fa.agent.app;

import com.fa.agent.api.dto.AgentDtos;
import com.fa.agent.app.tools.DraftTools;
import com.fa.agent.app.tools.ExportTools;
import com.fa.agent.app.tools.QueryTools;
import com.fa.agent.app.tools.ToolSpec;
import com.fa.agent.app.tools.WriteTools;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 全量工具表。启动时装配一次，之后只读。
 *
 * <p><b>为什么要按 level 过滤再下发</b>：不是「界面上少显示一个按钮」，
 * 而是让模型根本看不到那些工具。把 L3 工具放进 catalog 再在执行时拒绝，
 * 模型会反复尝试（它不知道自己没权限），白白烧 token 还给出「正在处理」的假承诺。
 *
 * <p>另一个约束：工具名全局唯一。重名在启动时就该炸，而不是运行时按 bean 顺序随机取一个。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolSpec> byName;
    private final AgentLevelService levels;

    public ToolRegistry(QueryTools queryTools, DraftTools draftTools, WriteTools writeTools,
                        ExportTools exportTools, AgentLevelService levels) {
        this.levels = levels;
        Map<String, ToolSpec> map = new LinkedHashMap<>();
        List<ToolSpec> all = new ArrayList<>();
        all.addAll(queryTools.specs());
        all.addAll(exportTools.specs());
        all.addAll(draftTools.specs());
        all.addAll(writeTools.specs());
        for (ToolSpec spec : all) {
            if (map.putIfAbsent(spec.name(), spec) != null) {
                throw new IllegalStateException("Agent 工具重名：" + spec.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    public ToolSpec require(String name) {
        ToolSpec spec = byName.get(name);
        if (spec == null) {
            // 不存在的工具按参数错误处理：这是调用方拼错了名字，不是「无权限」
            throw new BizException(ErrorCode.NOT_FOUND, "未知工具：" + name);
        }
        return spec;
    }

    /** 当前用户可见的工具清单（按级别开关过滤）。 */
    public List<AgentDtos.CatalogItem> catalog() {
        Map<String, Boolean> enabled = levels.levels();
        List<AgentDtos.CatalogItem> items = new ArrayList<>();
        for (ToolSpec spec : byName.values()) {
            if (!Boolean.TRUE.equals(enabled.get(spec.level()))) {
                continue;
            }
            items.add(new AgentDtos.CatalogItem(
                    spec.name(), spec.level(), spec.title(), spec.description(),
                    spec.requiresConfirm(), spec.undoable(),
                    spec.paramsSchema(), spec.returnsSchema()));
        }
        return items;
    }
}
