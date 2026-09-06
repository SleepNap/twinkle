package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 每频道已完成加载的同屏成员。只在进图确认后互发生成包，离图和断线立即移除。 */
public final class PlayerVisibilityService {
    private final PlayerSessionRegistry sessions;
    private final Map<PacketSession, Presence> visible = new HashMap<>();

    private record Presence(PlayerCharacter character, MapleMap map) { }

    public PlayerVisibilityService(PlayerSessionRegistry sessions) { this.sessions = sessions; }

    public void enter(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || sessions.get(character.getId()) != session
                || session.getAttr("mapTransition") != null || character.getMapObject() == null) return;
        MapleMap map = character.getMapObject();
        // 在可见性锁外构造快照，锁内不申请角色锁，避免换图/断线与角色写操作锁序反转。
        Map<PacketSession, List<OutPacket>> snapshots = new HashMap<>();
        for (var member : map.characters()) {
            PacketSession peer = sessions.get(member.getId());
            if (peer == null || peer.getAttr("character") != member || !(member instanceof PlayerCharacter player)) continue;
            snapshots.put(peer, snapshot(player));
        }
        List<OutPacket> own = snapshots.get(session);
        if (own == null) return;
        synchronized (this) {
            if (sessions.get(character.getId()) != session || character.getMapObject() != map
                    || session.getAttr("mapTransition") != null) return;
            Presence previous = visible.get(session);
            if (previous != null && previous.map() == map) return; // 重复确认不重复生成
            leave(session);
            for (var entry : visible.entrySet()) {
                if (entry.getValue().map() != map || !snapshots.containsKey(entry.getKey())) continue;
                snapshots.get(entry.getKey()).forEach(session::send);
                own.forEach(entry.getKey()::send);
            }
            visible.put(session, new Presence(character, map));
            session.setAttr("mapVisibilityReady", true);
        }
    }

    /** 不依赖当前 stage/map；使用进入时的归属，换频道或旧连接关闭不会误删新连接。 */
    public synchronized void leave(PacketSession session) {
        Presence departed = visible.remove(session);
        if (departed == null) return;
        session.setAttr("mapVisibilityReady", false);
        OutPacket packet = PlayerPresencePackets.remove(departed.character().getId());
        visible.forEach((peer, presence) -> {
            if (presence.map() == departed.map()) peer.send(packet);
        });
    }

    private static List<OutPacket> snapshot(PlayerCharacter character) {
        synchronized (character) {
            List<OutPacket> packets = new ArrayList<>();
            packets.add(PlayerPresencePackets.spawn(character));
            Map<Long, Integer> buffs = new TreeMap<>();
            long now = System.currentTimeMillis();
            if (character.getHp() > 0) character.buffs().forEach((mask, buff) -> {
                if (buff.expiresAt() > now) buffs.put(mask, buff.value());
            });
            if (!buffs.isEmpty()) packets.add(PlayerPresencePackets.buff(character.getId(), buffs));
            return List.copyOf(packets);
        }
    }
}
