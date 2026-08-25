package org.gms.coordinator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.gms.service.intercoord.IntercoordService.PlayerActivity;
import org.gms.service.intercoord.IntercoordService.PlayerPresence;

/**
 * 大区玩家 Presence 表（coordinator 维护 player → world + 连接属主频道 + 活动状态）。
 *
 * <p>CC 换频道、悄悄话、加好友前先查"人在哪个频道"。接口不假设进程内（铁律 1）：
 * 单进程内本类直接内存查表；M6 分布式换网络实现（RPC 到 coordinator），接口不变。
 *
 * <p>真值方向：channel 进图注册、真正断链注销、商城/MTS 切活动状态、CC 完成后更新属主。
 * 单一属主——Presence 真值只在 coordinator，
 * 频道不持副本（架构 4.4 铁律：共享状态绝不做"每频道各持一份靠同步保持一致"）。
 */
public final class LocationTable {

    private final ConcurrentMap<Long, PlayerPresence> presence = new ConcurrentHashMap<>();

    /** 玩家进图或目标频道完成认领。 */
    public void register(long playerId, int worldId, int ownerChannelId) {
        presence.put(playerId, new PlayerPresence(playerId, worldId, ownerChannelId,
                PlayerActivity.IN_CHANNEL, null));
    }

    /** 玩家下线注销（幂等）。 */
    public void remove(long playerId) {
        presence.remove(playerId);
    }

    /** 玩家换频道更新定位。 */
    public void move(long playerId, int channelId) {
        presence.computeIfPresent(playerId, (id, old) -> new PlayerPresence(id, old.worldId(), channelId,
                PlayerActivity.IN_CHANNEL, null));
    }

    /** 旧频道仍持有连接，目标频道尚未认领。 */
    public void beginChannelTransfer(long playerId, int sourceChannelId, int targetChannelId) {
        presence.computeIfPresent(playerId, (id, old) -> new PlayerPresence(id, old.worldId(),
                sourceChannelId, PlayerActivity.CHANNEL_TRANSITION, targetChannelId));
    }

    /** 商城/MTS 与频道游戏态切换，不改变物理连接属主。 */
    public void updateActivity(long playerId, PlayerActivity activity) {
        presence.computeIfPresent(playerId, (id, old) -> new PlayerPresence(id, old.worldId(),
                old.ownerChannelId(), activity,
                activity == PlayerActivity.CHANNEL_TRANSITION ? old.targetChannelId() : null));
    }

    public Optional<PlayerPresence> presence(long playerId) {
        return Optional.ofNullable(presence.get(playerId));
    }

    /** 查询持有玩家 TCP 会话的频道（在线则 present）。 */
    public Optional<Integer> locate(long playerId) {
        return presence(playerId).map(PlayerPresence::ownerChannelId);
    }

    /** 某频道在线玩家数（观测/负载均衡用）。 */
    public int onlineOnChannel(int channelId) {
        return (int) presence.values().stream()
                .filter(p -> p.ownerChannelId() == channelId && p.activity() == PlayerActivity.IN_CHANNEL)
                .count();
    }

    /** 物理连接归属统计，包含商城/MTS/迁移中。 */
    public int sessionsOnChannel(int channelId) {
        return (int) presence.values().stream().filter(p -> p.ownerChannelId() == channelId).count();
    }

    /** 大区在线统计，包含所有活动状态。 */
    public int onlineInWorld(int worldId) {
        return (int) presence.values().stream().filter(p -> p.worldId() == worldId).count();
    }

    /** 全部 Presence 快照（观测/管理用）。 */
    public Map<Long, PlayerPresence> snapshot() {
        return Map.copyOf(presence);
    }

    /** 当前在线玩家总数（观测）。 */
    public int onlineCount() {
        return presence.size();
    }
}
