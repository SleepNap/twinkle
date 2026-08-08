package org.gms.coordinator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家定位表（架构 4.4 三机制之一：coordinator 维护 player → channel）。
 *
 * <p>CC 换频道、悄悄话、加好友前先查"人在哪个频道"。接口不假设进程内（铁律 1）：
 * 单进程内本类直接内存查表；M6 分布式换网络实现（RPC 到 coordinator），接口不变。
 *
 * <p>真值方向：channel 进图注册、断链注销、CC 迁移更新。单一属主——定位表真值只在 coordinator，
 * 频道不持副本（架构 4.4 铁律：共享状态绝不做"每频道各持一份靠同步保持一致"）。
 */
public final class LocationTable {

    private final ConcurrentMap<Long, Integer> location = new ConcurrentHashMap<>(); // playerId → channelId

    /** 玩家进图/换频道后登记定位。 */
    public void register(long playerId, int channelId) {
        location.put(playerId, channelId);
    }

    /** 玩家下线注销（幂等）。 */
    public void remove(long playerId) {
        location.remove(playerId);
    }

    /** 玩家换频道更新定位。 */
    public void move(long playerId, int channelId) {
        location.put(playerId, channelId);
    }

    /** 查询玩家所在频道（在线则 present）。 */
    public Optional<Integer> locate(long playerId) {
        return Optional.ofNullable(location.get(playerId));
    }

    /** 某频道在线玩家数（观测/负载均衡用）。 */
    public int onlineOnChannel(int channelId) {
        return (int) location.values().stream().filter(c -> c == channelId).count();
    }

    /** 全部定位快照（playerId → channelId，观测/管理用）。 */
    public Map<Long, Integer> snapshot() {
        return Map.copyOf(location);
    }

    /** 当前在线玩家总数（观测）。 */
    public int onlineCount() {
        return location.size();
    }
}
