package org.gms.plugin;

/**
 * 贡献点类型（架构 7.1：贡献点从第一天版本化，红线 13）。
 *
 * <p>七类贡献点统一声明，可装卸即兼容面。M4 已接线的四类：PACKET_HANDLER / EVENT_LISTENER /
 * TICK_HANDLER / SCRIPT_NAMESPACE / LOGIC_SYSTEM；AI_TOOL 与 HTTP_ENDPOINT 先声明、接线留 M5
 * （AI 工具要重建 AiServices 代理、HTTP 路由与 Micronaut 编译期 DI 冲突，见 M4 计划决策）。
 *
 * <p>{@link #code()} 与 manifest 中 {@code contribution.N.type} 的值一致（如 {@code packet-handler}）。
 */
public enum ContributionType {

    PACKET_HANDLER("packet-handler"),
    EVENT_LISTENER("event-listener"),
    TICK_HANDLER("tick-handler"),
    SCRIPT_NAMESPACE("script-namespace"),
    LOGIC_SYSTEM("logic-system"),
    AI_TOOL("ai-tool"),
    HTTP_ENDPOINT("http-endpoint");

    private final String code;

    private ContributionType(String code) {
        this.code = code;
    }

    /** manifest 中的类型标识。 */
    public String code() {
        return code;
    }

    /** 按 manifest 值解析；未知值抛 {@link IllegalArgumentException}（拒载 + 明确日志）。 */
    public static ContributionType fromCode(String code) {
        for (ContributionType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知贡献点类型: " + code);
    }
}
