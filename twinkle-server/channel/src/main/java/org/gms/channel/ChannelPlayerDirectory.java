package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Worker 内全部频道玩家表的只读聚合目录。
 *
 * <p>频道运行态仍各自持有独立 {@link PlayerStorage}；本目录只给进程级存档队列、管理查询和
 * 优雅关停提供聚合视图，禁止游戏 handler 经它跨频道修改玩家。
 */
public final class ChannelPlayerDirectory {

    private final ConcurrentMap<Integer, PlayerStorage> storages = new ConcurrentHashMap<>();

    public void register(int channelId, PlayerStorage storage) {
        PlayerStorage previous = storages.putIfAbsent(channelId, storage);
        if (previous != null && previous != storage) {
            throw new IllegalArgumentException("Duplicate channel player storage: " + channelId);
        }
    }

    public void unregister(int channelId, PlayerStorage storage) {
        storages.remove(channelId, storage);
    }

    public PlayerStorage storage(int channelId) {
        return storages.get(channelId);
    }

    public Collection<PlayerCharacter> allPlayers() {
        return storages.values().stream().flatMap(storage -> storage.all().stream()).toList();
    }

    public Map<Integer, PlayerStorage> snapshot() {
        return Map.copyOf(storages);
    }

    public List<Integer> channelIds() {
        return storages.keySet().stream().sorted().toList();
    }
}
