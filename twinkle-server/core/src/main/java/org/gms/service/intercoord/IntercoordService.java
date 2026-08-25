package org.gms.service.intercoord;

import java.util.Map;
import java.util.Optional;

/**
 * 频道间交互三机制服务接口（架构 4.4：单一属主 / 消息总线 / 定位表）。
 *
 * <p>接口放 core（稳定底座），coordinator 实现、channel 消费——channel 与 coordinator
 * 都依赖 core，不互相依赖（接口先行、分布式后置，铁律 1）。M6 分布式时本接口的进程内实现
 * 换成 RPC 桩（复用到 coordinator），频道侧调用面零变化。
 *
 * <p>与 {@code AdminService} 同范式：管理侧/游戏域经 core 服务接口交互，不经对方模块具体类。
 */
public interface IntercoordService {

    // ---- 大区在线 Presence（player → world + 连接属主频道 + 活动状态） ----

    /**
     * 玩家首次进图或完成频道迁移后登记大区在线态。
     *
     * <p>{@code ownerChannelId} 表示持有 TCP 会话的频道进程，不等于玩家一定在该频道地图内。
     */
    void registerPlayer(long playerId, int worldId, int ownerChannelId);

    /** 单世界兼容入口；新代码应显式传 worldId。 */
    default void registerPlayer(long playerId, int ownerChannelId) {
        registerPlayer(playerId, 0, ownerChannelId);
    }

    /** 玩家真正断开并离开大区时注销（幂等）；进入商城/MTS 不得调用。 */
    void unregisterPlayer(long playerId);

    /** 玩家完成换频道、目标频道已接管 TCP 会话后更新属主。 */
    void movePlayer(long playerId, int channelId);

    /**
     * 开始换频道：旧频道仍是连接属主，玩家退出地图/频道游戏表，但保持大区在线。
     * 目标频道只有在客户端重连并完成认领后才能成为新属主。
     */
    void beginChannelTransfer(long playerId, int sourceChannelId, int targetChannelId);

    /** 在同一频道 TCP 连接上切换活动状态（商城/MTS/返回频道）。 */
    void updatePlayerActivity(long playerId, PlayerActivity activity);

    /** 查询完整大区在线态。 */
    Optional<PlayerPresence> presence(long playerId);

    /** 查询持有玩家 TCP 会话的频道（商城/MTS 中仍 present）。 */
    Optional<Integer> locate(long playerId);

    /** 某频道内实际游戏玩家数（只统计 IN_CHANNEL，不含商城/MTS/迁移中）。 */
    int onlineOnChannel(int channelId);

    /** 某频道持有的 TCP 会话数（包含商城/MTS/迁移中）。 */
    int sessionsOnChannel(int channelId);

    /** 某大区在线玩家数（包含频道、商城、MTS 和迁移中）。 */
    int onlineInWorld(int worldId);

    /** 玩家在大区内的活动位置；物理连接始终由 ownerChannelId 指向的频道持有。 */
    enum PlayerActivity {
        IN_CHANNEL,
        CASH_SHOP,
        MTS,
        CHANNEL_TRANSITION
    }

    /** 大区在线真值；targetChannelId 仅在 CHANNEL_TRANSITION 时非空。 */
    record PlayerPresence(long playerId, int worldId, int ownerChannelId,
                          PlayerActivity activity, Integer targetChannelId) {
    }

    // ---- 频道注册 ----

    /** 频道上报（启动/心跳更新）。 */
    void registerChannel(int channelId, String host, int port, int onlineCount);

    /** 频道心跳续期。 */
    void heartbeatChannel(int channelId, int onlineCount);

    /** 查询频道信息。 */
    Optional<ChannelInfo> channel(int channelId);

    /**
     * 全部频道注册表快照（管理控制台"频道状态"列表用，架构 4.6.4 注册中心）。
     *
     * @return channelId → 频道信息（未上报/已下线的频道不出现）
     */
    Map<Integer, ChannelInfo> channels();

    /** 频道信息（M4 进程内：在线会话数；M6 扩展 host:port 网络端点）。 */
    public record ChannelInfo(int channelId, String host, int port, int onlineCount) {
    }

    // ---- 单一属主存储（共享状态真值只在 coordinator） ----

    /** 读取真值。 */
    Optional<StoreEntry> read(String key);

    /**
     * 写入真值（带版本：调用方读到版本后修改，写入校验仍为该版本，冲突拒绝——防覆盖）。
     *
     * @return 新版本号
     */
    long write(String key, Object value, long expectedVersion);

    /** 原子自增（counter 账本：商店资金等）。返回新值。 */
    long increment(String key, long delta);

    /** 全部真值快照（观测/管理用）。 */
    Map<String, StoreEntry> storeSnapshot();

    /** 单条存储项：值 + 版本号。 */
    public record StoreEntry(Object value, long version) {
    }
}
