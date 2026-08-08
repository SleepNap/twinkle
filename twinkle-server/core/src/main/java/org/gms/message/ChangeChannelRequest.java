package org.gms.message;

/**
 * 换频道（CC）请求（架构 4.7：一机制两用——功能换频道 + 兜底升级挪人）。
 *
 * <p>经消息总线发目标频道；可靠总线保证"不掉数据、不重复"（架构 4.5 三件套：
 * 持久化队列 + 幂等去重 + 单一属主序号）。
 *
 * @param playerId  迁移玩家角色 id
 * @param fromChannel 当前频道
 * @param toChannel 目标频道
 * @param reason    迁移原因（PLAYER_CHANGE / MAINTENANCE）
 */
public record ChangeChannelRequest(long playerId, int fromChannel, int toChannel, Reason reason) {

    public enum Reason {
        /** 玩家主动换频道。 */
        PLAYER_CHANGE,
        /** 运维兜底：升级/重启前把玩家挪走。 */
        MAINTENANCE
    }
}
