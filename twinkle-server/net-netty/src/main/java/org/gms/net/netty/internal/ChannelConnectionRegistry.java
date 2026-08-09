package org.gms.net.netty.internal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内部通信连接注册表（架构 4.6.4 注册中心：channel → 连接，coordinator 内建）。
 *
 * <p>coordinator 端维护：频道连接（channelId → connection）+ 管理进程连接（admin）。
 * 每连接建立时经 REGISTER 帧上报身份，断链自动移除（幂等）。
 *
 * <p>星形拓扑：所有进程连接 coordinator，coordinator 是中心路由器（架构 4.5）。
 */
public final class ChannelConnectionRegistry {

    private static final Logger LOG = LogManager.getLogger(ChannelConnectionRegistry.class);

    private final ConcurrentMap<Integer, InternalConnection> channels = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> channelHosts = new ConcurrentHashMap<>();

    private volatile InternalConnection admin;

    /** 频道连接注册（REGISTER 帧：channelId>0）。 */
    public void registerChannel(int channelId, String host, int port, InternalConnection conn) {
        channels.put(channelId, conn);
        channelHosts.put(channelId, host + ":" + port);
        LOG.info("频道连接注册: channel={} 端点={}", channelId, host + ":" + port);
    }

    /** 管理进程连接注册（REGISTER 帧：admin=true）。 */
    public void registerAdmin(InternalConnection conn) {
        admin = conn;
        LOG.info("管理进程连接注册");
    }

    /** 断链移除（幂等，防旧连接迟到断链误删新连接）。 */
    public void unregister(InternalConnection conn) {
        if (admin == conn) {
            admin = null;
            return;
        }
        channels.entrySet().removeIf(e -> e.getValue() == conn);
    }

    /** 按频道取连接。 */
    public InternalConnection channel(int channelId) {
        return channels.get(channelId);
    }

    /** 管理进程连接。 */
    public InternalConnection admin() {
        return admin;
    }

    /** 全部频道连接快照。 */
    public Map<Integer, InternalConnection> channelsSnapshot() {
        return Map.copyOf(channels);
    }
}
