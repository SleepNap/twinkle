package org.gms.ai.model.tool;

import java.util.Locale;

/**
 * 本地规则模型的工具路由（架构 M3-2：确定性路由代替 LLM function calling 决策）。
 *
 * <p>把用户自然语言指令映射到 {@code @Tool} 方法名。规则简单可扩展：匹配关键词 → 返回
 * 工具名；未命中返回 null（模型直接文本回答）。真实 LLM 接入后本类可禁用（模型原生决策）。
 */
public final class ToolRouter {

    /** 在线统计工具。 */
    public static final String ONLINE_STATS = "queryOnlineStats";

    /** 查询意图路由：命中返回工具名，否则 null。 */
    public String route(String userText) {
        if (userText == null) {
            return null;
        }
        String t = userText.toLowerCase(Locale.ROOT);
        // "在线/统计/人数/报表" → 在线统计工具
        for (String kw : new String[]{"在线", "统计", "人数", "报表", "online", "stats"}) {
            if (t.contains(kw)) {
                return ONLINE_STATS;
            }
        }
        return null;
    }

    /** 工具调用参数（本地模型传 JSON 字符串；此处在线统计无需参数，传空对象）。 */
    public String argumentsFor(String toolName, String userText) {
        return "{}";
    }
}
