package org.gms.coordinator;

import org.gms.service.intercoord.IntercoordService;

import java.util.Map;
import java.util.Optional;

/**
 * 频道间交互三机制服务实现（架构 4.4：单一属主 / 消息总线 / 定位表）。
 *
 * <p>coordinator 内建三机制，进程内直接持有真值（定位表 + 单一属主存储 + 频道注册）。
 * channel 经 {@link IntercoordService} 接口调用本实现——不依赖 coordinator 模块具体类。
 */
public final class CoordinatorService implements IntercoordService {

    private final LocationTable locationTable;
    private final ChannelRegistry channelRegistry;
    private final SingleOwnerStore singleOwnerStore;

    public CoordinatorService(LocationTable locationTable, ChannelRegistry channelRegistry,
                              SingleOwnerStore singleOwnerStore) {
        this.locationTable = locationTable;
        this.channelRegistry = channelRegistry;
        this.singleOwnerStore = singleOwnerStore;
    }

    @Override
    public void registerPlayer(long playerId, int channelId) {
        locationTable.register(playerId, channelId);
    }

    @Override
    public void unregisterPlayer(long playerId) {
        locationTable.remove(playerId);
    }

    @Override
    public void movePlayer(long playerId, int channelId) {
        locationTable.move(playerId, channelId);
    }

    @Override
    public Optional<Integer> locate(long playerId) {
        return locationTable.locate(playerId);
    }

    @Override
    public int onlineOnChannel(int channelId) {
        return locationTable.onlineOnChannel(channelId);
    }

    @Override
    public void registerChannel(int channelId, String host, int port, int onlineCount) {
        channelRegistry.register(channelId, host, port, onlineCount);
    }

    @Override
    public void heartbeatChannel(int channelId, int onlineCount) {
        channelRegistry.heartbeat(channelId, onlineCount);
    }

    @Override
    public Optional<ChannelInfo> channel(int channelId) {
        return channelRegistry.get(channelId).map(info ->
                new ChannelInfo(info.channelId(), info.host(), info.port(), info.onlineCount()));
    }

    @Override
    public Optional<StoreEntry> read(String key) {
        return singleOwnerStore.get(key).map(e -> new StoreEntry(e.value(), e.version()));
    }

    @Override
    public long write(String key, Object value, long expectedVersion) {
        return singleOwnerStore.put(key, value, expectedVersion);
    }

    @Override
    public long increment(String key, long delta) {
        return singleOwnerStore.increment(key, delta);
    }

    @Override
    public Map<String, StoreEntry> storeSnapshot() {
        Map<String, SingleOwnerStore.Entry> raw = singleOwnerStore.snapshot();
        Map<String, StoreEntry> out = new java.util.HashMap<>();
        raw.forEach((k, v) -> out.put(k, new StoreEntry(v.value(), v.version())));
        return Map.copyOf(out);
    }
}
