package org.gms.coordinator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道注册表（架构 4.6.4 注册中心：channel → host 的登记/心跳，coordinator 内建服务）。
 *
 * <p>channel 启动上报（频道 id、host、在线玩家数），心跳维护；coordinator 单点（2C2G 红线内建最省）。
 * M4 单进程内：频道以"频道 id → 该频道的在线会话数"登记（定位表已记录 player→channel，
 * 本表记录 channel 本身的状态）。M6 分布式时扩展为 host:port 网络端点。
 *
 * <p>消费方：admin / http 需要知道频道在哪 → 问 coordinator；login 需要知道频道列表 → 问 coordinator。
 */
public final class ChannelRegistry {

    private final ConcurrentMap<Integer, ChannelInfo> channels = new ConcurrentHashMap<>();

    /** 频道登记信息（M4 进程内：在线会话数；M6 扩展 host:port 端点）。 */
    public record ChannelInfo(int channelId, String host, int port, int onlineCount, String workerId) {
        public ChannelInfo(int channelId, String host, int port, int onlineCount) {
            this(channelId, host, port, onlineCount, "");
        }
    }

    /** 频道上报（启动/心跳更新）。 */
    public void register(int channelId, String host, int port, int onlineCount) {
        register(channelId, host, port, onlineCount, "");
    }

    public void register(int channelId, String host, int port, int onlineCount, String workerId) {
        validate(channelId, host, port);
        channels.compute(channelId, (id, existing) -> {
            String owner = workerId == null ? "" : workerId;
            if (existing != null && !existing.workerId().isBlank() && !owner.isBlank()
                    && !existing.workerId().equals(owner)) {
                throw new IllegalStateException("Channel " + channelId + " is already owned by worker "
                        + existing.workerId());
            }
            return new ChannelInfo(channelId, host, port, onlineCount, owner);
        });
    }

    /** 频道心跳续期（更新在线数）。 */
    public void heartbeat(int channelId, int onlineCount) {
        channels.computeIfPresent(channelId, (k, info) ->
                new ChannelInfo(k, info.host(), info.port(), onlineCount, info.workerId()));
    }

    /** 频道下线注销（幂等）。 */
    public void unregister(int channelId) {
        channels.remove(channelId);
    }

    /** 查询频道信息（未上报返回 empty）。 */
    public Optional<ChannelInfo> get(int channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    /** 全部频道快照（登录服列表用，架构 4.6.4：login 问 coordinator 拿频道列表）。 */
    public Map<Integer, ChannelInfo> snapshot() {
        return Map.copyOf(channels);
    }

    /** 已登记频道数。 */
    public int count() {
        return channels.size();
    }

    private static void validate(int channelId, String host, int port) {
        if (channelId < 1 || channelId > 256) {
            throw new IllegalArgumentException("channelId must be representable by v83 (1..256): " + channelId);
        }
        if (host == null || host.isBlank()) throw new IllegalArgumentException("channel host is blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("channel port out of range: " + port);
    }
}
