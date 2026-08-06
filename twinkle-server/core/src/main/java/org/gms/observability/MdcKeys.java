package org.gms.observability;

/**
 * 结构化日志统一字段（架构 12.3）：MDC key 契约。
 *
 * <p>log4j2 结构化布局（JSON/字段式）在 M3 管理进程 HTTP 落地时启用，见 bootstrap 的
 * {@code log4j2.xml} 注释模板。各模块埋点前先 put 这些 key、处理结束清理；字段缺失时
 * 布局输出空串。M2 起：tick / 包处理入口 set traceId + channelId，玩家相关操作 set playerId（脱敏）。
 */
public final class MdcKeys {

    /** 链路 ID（跨进程复用消息全局 ID，架构 12.2）。 */
    public static final String TRACE_ID = "traceId";
    /** 频道 ID。 */
    public static final String CHANNEL_ID = "channelId";
    /** 玩家 ID（脱敏）。 */
    public static final String PLAYER_ID = "playerId";

    private MdcKeys() {
    }
}
