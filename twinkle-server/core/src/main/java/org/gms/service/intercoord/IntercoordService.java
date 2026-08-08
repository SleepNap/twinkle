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

    // ---- 定位表（player → channel） ----

    /** 玩家进图/换频道后登记定位。 */
    void registerPlayer(long playerId, int channelId);

    /** 玩家下线注销（幂等）。 */
    void unregisterPlayer(long playerId);

    /** 玩家换频道更新定位。 */
    void movePlayer(long playerId, int channelId);

    /** 查询玩家所在频道（在线则 present）。 */
    Optional<Integer> locate(long playerId);

    /** 某频道在线玩家数。 */
    int onlineOnChannel(int channelId);

    // ---- 频道注册 ----

    /** 频道上报（启动/心跳更新）。 */
    void registerChannel(int channelId, String host, int port, int onlineCount);

    /** 频道心跳续期。 */
    void heartbeatChannel(int channelId, int onlineCount);

    /** 查询频道信息。 */
    Optional<ChannelInfo> channel(int channelId);

    /** 频道信息（M4 进程内：在线会话数；M6 扩展 host:port 网络端点）。 */
    record ChannelInfo(int channelId, String host, int port, int onlineCount) {
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
    record StoreEntry(Object value, long version) {
    }
}
