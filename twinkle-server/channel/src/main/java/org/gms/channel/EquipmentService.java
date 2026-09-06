package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.net.packet.v83.V83ItemSnapshot;
import org.gms.replaceable.EquipmentSystem;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 每频道的换装封包编排；派生属性不落库，存档保留基础属性与完整装备实例。 */
public final class EquipmentService {
    private final EquipmentSystem equipment;
    private final PlayerSessionRegistry sessions;
    private final Clock clock;

    public EquipmentService(EquipmentSystem equipment, PlayerSessionRegistry sessions, Clock clock) {
        this.equipment = equipment;
        this.sessions = sessions;
        this.clock = clock;
    }

    public boolean move(PacketSession session, short source, short target, int quantity) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null) return false;
        if (sessions.get(character.getId()) != session || !GameplaySession.canAct(session, character)) return false;
        int hp = character.getHp(), mp = character.getMp();
        EquipmentSystem.Change change = equipment.move(character, source, target, quantity, clock.millis());
        if (change == null) return false;
        scheduleRefresh(session, character);
        Map<Short, V83ItemSnapshot> bound = new HashMap<>();
        change.boundSlots().forEach(slot -> bound.put(slot,
                ChannelItemProtocolMapper.toSnapshot(character.getInventory(InventoryType.EQUIP).getItem(slot))));
        session.send(GameplayPackets.equipmentMoves(change.moves(), bound));
        changedHealth(session, character, hp, mp);
        sessions.broadcastToMap(character.getMapObject(), PlayerPresencePackets.changedLook(character), character.getId());
        return true;
    }

    public void initialize(PacketSession session) { refresh(session, true); }
    public void refresh(PacketSession session) { refresh(session, false); }

    /** 登录时即恢复加成；每秒清理穿戴到期实例并刷新 WZ 换代后的投影。 */
    private void refresh(PacketSession session, boolean initializing) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null) return;
        if (session.stage() != SessionStage.IN_GAME || sessions.get(character.getId()) != session
                || !initializing && (session.getAttr("mapTransition") != null || session.getAttr("trade") != null)) return;
        Long next = session.getAttr("equipmentExpiresAt"), version = session.getAttr("equipmentDataVersion");
        if (!initializing && next != null && next > clock.millis()
                && version != null && version == equipment.dataVersion()) return;
        int hp = character.getHp(), mp = character.getMp();
        var before = GameplayPackets.inventory(character, InventoryType.EQUIP);
        boolean expired = equipment.expire(character, clock.millis());
        boolean refreshed = equipment.refresh(character, clock.millis());
        if (!refreshed && !expired) return;
        if (expired) {
            GameplayPackets.inventoryChanges(InventoryType.EQUIP, before,
                    GameplayPackets.inventory(character, InventoryType.EQUIP)).forEach(session::send);
            if (character.getMapObject() != null) sessions.broadcastToMap(character.getMapObject(),
                    PlayerPresencePackets.changedLook(character), character.getId());
        }
        changedHealth(session, character, hp, mp);
        if (refreshed) scheduleRefresh(session, character);
        else session.setAttr("equipmentExpiresAt", null);
    }

    /** 常驻扫描仅比较版本和最近到期时间；未换装/未到期时不复制整份背包。 */
    private void scheduleRefresh(PacketSession session, PlayerCharacter character) {
        long next = character.equipmentItems().entrySet().stream()
                .filter(entry -> entry.getKey() < 0 && entry.getValue().expiration() >= 0)
                .mapToLong(entry -> entry.getValue().expiration()).min().orElse(Long.MAX_VALUE);
        session.setAttr("equipmentExpiresAt", next);
        session.setAttr("equipmentDataVersion", equipment.dataVersion());
    }

    private static void changedHealth(PacketSession session, PlayerCharacter character, int oldHp, int oldMp) {
        Map<Integer, Integer> changes = new HashMap<>();
        if (oldHp != character.getHp()) changes.put(GameplayPackets.HP, character.getHp());
        if (oldMp != character.getMp()) changes.put(GameplayPackets.MP, character.getMp());
        if (!changes.isEmpty()) session.send(GameplayPackets.stats(changes));
    }
}
