package org.gms.service.admin;

import org.gms.service.intercoord.IntercoordService.PlayerActivity;

/**
 * 在线状态变更事件（架构 M3-1 数据三路第③路：事件驱动快照的载荷）。
 *
 * <p>频道进程把进图/下线推给管理进程的只读镜像（EventBus 广播），管理侧 HTTP 读镜像。
 * 载荷只含 DTO 字段，不含内存对象。事件类型放公共底座（跨进程契约，铁律 1）。
 */
public final class OnlinePlayerEvents {

    private OnlinePlayerEvents() {
    }

    /** 玩家进图。 */
    public record PlayerOnline(long characterId, String name, int mapId, int level, int job) {
    }

    /** 玩家下线。 */
    public record PlayerOffline(long characterId) {
    }

    /**
     * 玩家仍在大区在线，仅切换频道内活动状态；商城/MTS 不得伪装成 PlayerOffline。
     * ownerChannelId 始终是持有 TCP 会话的频道。
     */
    public record PlayerActivityChanged(long characterId, int worldId, int ownerChannelId,
                                        PlayerActivity activity) {
    }

    /** 事件广播目标（精确匹配，EventBus 订阅用）。 */
    public static final String TARGET = "online-player-events";
}
