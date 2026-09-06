package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/** 游戏用例测试的内存连接，不依赖真实客户端或外部数据库。 */
public final class GameplayTestSession implements PacketSession {
    public final PlayerCharacter character;
    public final List<OutPacket> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile SessionStage stage = SessionStage.IN_GAME;

    public GameplayTestSession(long id, MapleMap map) {
        character = new PlayerCharacter(1);
        character.setId(id);
        character.setName("Player" + id);
        character.setMapObject(map);
        character.setMap(map.getMapId());
        character.setHp(50);
        character.setMaxHp(100);
        map.addCharacter(character);
        attributes.put("character", character);
    }
    @Override public void send(OutPacket packet) { sent.add(packet); }
    @Override public void close(String reason) { stage = SessionStage.HANDSHAKE; }
    @Override public SessionStage stage() { return stage; }
    @Override public void transition(SessionStage value) { stage = value; }
    @Override public long sessionId() { return character.getId(); }
    @SuppressWarnings("unchecked")
    @Override public <T> T getAttr(String key) { return (T) attributes.get(key); }
    @Override public void setAttr(String key, Object value) {
        if (value == null) attributes.remove(key); else attributes.put(key, value);
    }
}
