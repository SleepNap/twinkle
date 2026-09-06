package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.v83.V83FileTime;
import org.gms.replaceable.ItemSystem;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 频道掉落物状态与原子认领。先锁角色再锁单个掉落，空间不足、重复拾取均不扣物品。 */
public final class GroundDropService implements AutoCloseable {
    public record Drop(int objectId, MapleMap map, long owner, int x, int y,
                       TradeItemSnapshot item, int mesos, long expiresAt) {
        public Drop {
            if (item == null ? mesos <= 0 : mesos != 0) throw new IllegalArgumentException("Invalid drop contents");
        }
    }
    private final Map<Integer, Map<Integer, Drop>> drops = new ConcurrentHashMap<>();
    private final ItemSystem items;
    private final GameDataProvider data;
    private final PlayerSessionRegistry sessions;
    private final Clock clock;

    public GroundDropService(ItemSystem items, GameDataProvider data, PlayerSessionRegistry sessions, Clock clock) {
        this.items = items;
        this.data = data;
        this.sessions = sessions;
        this.clock = clock;
    }

    public boolean drop(PlayerCharacter character, byte type, short slot, int quantity) {
        synchronized (character) {
            if (!active(character)) return false;
            TradeItemSnapshot item = items.snapshotTradeItem(character, type, slot, quantity);
            if (item == null || item.cashId() != 0 || item.petId() != 0 || item.flag() != 0) return false;
            var definition = data.item(item.itemId());
            if (definition == null || definition.isTradeBlock() || type == InventoryType.CASH.getType()) return false;
            MapleMap map = character.getMapObject();
            Map<Integer, Drop> visible = drops.computeIfAbsent(map.getMapId(), ignored -> new ConcurrentHashMap<>());
            synchronized (visible) {
                if (visible.size() >= 1000) return false;
                int objectId = map.nextObjectId();
                Drop drop = new Drop(objectId, map, character.getId(), character.getX(), character.getY(),
                        item, 0, clock.millis() + 120_000);
                if (!items.takeTradeItems(character, List.of(item))) return false;
                visible.put(objectId, drop);
                broadcast(map, spawn(drop));
                return true;
            }
        }
    }

    public boolean pickup(PlayerCharacter character, int objectId) {
        synchronized (character) {
            if (!active(character)) return false;
            Map<Integer, Drop> visible = drops.get(character.getMap());
            Drop drop = visible == null ? null : visible.get(objectId);
            if (drop == null || drop.map() != character.getMapObject()
                    || !GameplaySession.near(character, drop.x(), drop.y(), 200)) return false;
            synchronized (drop) {
                if (visible.get(objectId) != drop || drop.expiresAt() <= clock.millis()
                        || drop.item() != null && drop.item().expiration() > 0 && drop.item().expiration() <= clock.millis()) return false;
                if (drop.mesos() > 0) {
                    if (!items.changeMeso(character, drop.mesos())) return false;
                    visible.remove(objectId, drop);
                    broadcast(drop.map(), remove(drop.objectId(), character.getId()));
                    sendBalance(character);
                    return true;
                }
                InventoryType type = InventoryType.getByType(drop.item().inventoryType());
                var before = GameplayPackets.inventory(character, type);
                if (!items.giveTradeItems(character, List.of(drop.item()))) return false;
                visible.remove(objectId, drop);
                broadcast(drop.map(), remove(drop.objectId(), character.getId()));
                PacketSession session = sessions.get(character.getId());
                if (session != null) GameplayPackets.inventoryChanges(type, before,
                        GameplayPackets.inventory(character, type)).forEach(session::send);
                return true;
            }
        }
    }

    public void enter(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null) return;
        drops.getOrDefault(character.getMap(), Map.of()).values().stream()
                .filter(drop -> drop.map() == character.getMapObject() && drop.expiresAt() > clock.millis())
                .forEach(drop -> session.send(spawn(drop)));
    }

    /** 手动金币掉落：单次 10–50000，扣款与发布同属一次操作；沿用公开拾取和两分钟过期规则。 */
    public boolean dropMesos(PlayerCharacter character, int amount) {
        synchronized (character) {
            if (!active(character) || amount < 10 || amount > 50000) return false;
            MapleMap map = character.getMapObject();
            Map<Integer, Drop> visible = drops.computeIfAbsent(map.getMapId(), ignored -> new ConcurrentHashMap<>());
            synchronized (visible) {
                if (visible.size() >= 1000) return false;
                Drop drop = new Drop(map.nextObjectId(), map, character.getId(), character.getX(), character.getY(),
                        null, amount, clock.millis() + 120_000);
                if (!items.changeMeso(character, -amount)) return false;
                visible.put(drop.objectId(), drop); sendBalance(character); broadcast(map, spawn(drop));
                return true;
            }
        }
    }

    private void sendBalance(PlayerCharacter character) {
        PacketSession session = sessions.get(character.getId());
        if (session != null) session.send(GameplayPackets.stats(Map.of(GameplayPackets.MESO, character.getMeso())));
    }

    private boolean active(PlayerCharacter character) {
        PacketSession session = sessions.get(character.getId());
        return session != null && GameplaySession.character(session) == character && GameplaySession.canAct(session, character);
    }

    public void expire() {
        for (Map<Integer, Drop> visible : drops.values()) {
            for (Drop drop : visible.values()) {
                synchronized (drop) {
                    if (drop.expiresAt() <= clock.millis() && visible.remove(drop.objectId(), drop))
                        broadcast(drop.map(), remove(drop.objectId(), 0));
                }
            }
        }
    }

    private void broadcast(MapleMap map, OutPacket packet) {
        sessions.broadcastToMap(map, packet);
    }

    public static OutPacket spawn(Drop drop) {
        ByteArrayOutPacket packet = GameplayPackets.packet(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        packet.writeByte(2);
        packet.writeInt(drop.objectId());
        packet.writeBool(drop.mesos() > 0);
        packet.writeInt(drop.mesos() > 0 ? drop.mesos() : drop.item().itemId());
        packet.writeInt(0);
        packet.writeByte(2);
        packet.writeShort(drop.x());
        packet.writeShort(drop.y());
        packet.writeInt((int) drop.owner());
        if (drop.item() != null) packet.writeLong(V83FileTime.encode(drop.item().expiration()));
        packet.writeByte(0);
        return packet;
    }

    private static OutPacket remove(int objectId, long picker) {
        ByteArrayOutPacket packet = GameplayPackets.packet(SendOpcode.REMOVE_ITEM_FROM_MAP);
        packet.writeByte(picker == 0 ? 0 : 2);
        packet.writeInt(objectId);
        if (picker != 0) packet.writeInt((int) picker);
        return packet;
    }

    @Override public void close() { drops.clear(); }
}
