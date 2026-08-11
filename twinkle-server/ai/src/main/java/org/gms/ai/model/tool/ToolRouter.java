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
    public static final String PLAYER_PROFILE = "queryPlayerProfile";
    public static final String PLAYER_INVENTORY = "queryPlayerInventory";
    public static final String ACCOUNT_STATUS = "queryAccountStatus";

    /** 查询意图路由：命中返回工具名，否则 null。 */
    public String route(String userText) {
        if (userText == null) {
            return null;
        }
        String t = userText.toLowerCase(Locale.ROOT);
        if (containsAny(t, "背包", "物品", "inventory", "item")) {
            return PLAYER_INVENTORY;
        }
        if (containsAny(t, "角色", "玩家", "金币", "地图", "profile", "character")) {
            return PLAYER_PROFILE;
        }
        if (containsAny(t, "账号", "封禁", "account", "banned")) {
            return ACCOUNT_STATUS;
        }
        // "在线/统计/人数/报表" → 在线统计工具
        if (containsAny(t, "在线", "统计", "人数", "报表", "online", "stats")) {
            return ONLINE_STATS;
        }
        return null;
    }

    /** 工具调用参数（本地模型传 JSON 字符串；此处在线统计无需参数，传空对象）。 */
    public String argumentsFor(String toolName, String userText) {
        if (ONLINE_STATS.equals(toolName)) {
            return "{}";
        }
        String name = extractName(userText);
        String parameter = ACCOUNT_STATUS.equals(toolName) ? "name" : "characterName";
        return "{\"" + parameter + "\":\"" + jsonEscape(name) + "\"}";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String extractName(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher playerContext = java.util.regex.Pattern
                .compile("当前玩家角色名=([A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,32})")
                .matcher(text);
        if (playerContext.find()) {
            return playerContext.group(1);
        }
        java.util.regex.Matcher quoted = java.util.regex.Pattern
                .compile("[「『\"']([A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,32})[」』\"']")
                .matcher(text);
        if (quoted.find()) {
            return quoted.group(1);
        }
        String[] parts = text.trim().split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].replaceAll("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]", "");
            if (!candidate.isBlank() && !containsAny(candidate.toLowerCase(Locale.ROOT),
                    "背包", "物品", "角色", "玩家", "账号", "查询", "看看", "inventory", "profile", "account")) {
                return candidate;
            }
        }
        return "";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
