package org.gms.channel;

import org.gms.domain.game.spi.CharacterState;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道会话注册表（角色 id → 连接会话，M3 广播/交易的前置缺件）。
 *
 * <p>进图时注册、断链/下线时注销。广播经 {@code MapleMap.characters()} 的角色 id
 * 反查会话发包——不做跨操作状态，只做查表（红线 12）。
 *
 * <p>游戏 tick 单线程，但连接断开关闭可能并发到达，用 {@link ConcurrentMap} 兜底。
 */
public final class PlayerSessionRegistry {

    private final ConcurrentMap<Long, PacketSession> sessions = new ConcurrentHashMap<>();

    /** 玩家进图登记会话。 */
    public void register(long characterId, PacketSession session) {
        sessions.put(characterId, session);
    }

    /** 玩家下线/断链注销。 */
    public void unregister(long characterId) {
        sessions.remove(characterId);
    }

    public PacketSession get(long characterId) {
        return sessions.get(characterId);
    }

    public int count() {
        return sessions.size();
    }

    /** 全部在线会话（不可变视图）。 */
    public Collection<PacketSession> all() {
        return List.copyOf(sessions.values());
    }

    /** 向一张地图内的全部玩家广播（可替换层/channel 用，地图内角色取会话发包）。 */
    public void broadcastToMap(org.gms.domain.game.map.MapleMap map, OutPacket packet) {
        broadcastToMap(map, packet, -1);
    }

    /** 向地图内除 excludeId 外的全部玩家广播（移动/攻击不回显给发包者自己）。 */
    public void broadcastToMap(org.gms.domain.game.map.MapleMap map, OutPacket packet, long excludeId) {
        for (CharacterState chr : map.characters()) {
            if (chr.getId() == excludeId) {
                continue;
            }
            PacketSession session = sessions.get(chr.getId());
            if (session != null) {
                session.send(packet);
            }
        }
    }
}
