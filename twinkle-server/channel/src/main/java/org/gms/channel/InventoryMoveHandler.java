package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ItemSystem;

/** 背包移动与丢弃；收包字段事实参考北斗，数量与实例转移由本项目状态操作实现。 */
public final class InventoryMoveHandler implements PacketHandler {
    private final ItemSystem items;
    private final GroundDropService drops;

    public InventoryMoveHandler(ItemSystem items, GroundDropService drops) {
        this.items = items;
        this.drops = drops;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        PlayerCharacter character = GameplaySession.character(session);
        try {
            if (!GameplaySession.canAct(session, character) || packet.available() < 11) return;
            packet.skip(4);
            byte inventoryType = packet.readByte();
            InventoryType type = InventoryType.getByType(inventoryType);
            short source = packet.readShort(), target = packet.readShort();
            int quantity = packet.readShort();
            if (type == InventoryType.UNDEFINED) return;
            synchronized (character) {
                var before = GameplayPackets.inventory(character, type);
                boolean changed = target == 0 ? drops.drop(character, inventoryType, source, quantity)
                        : items.moveItem(character, inventoryType, source, target, quantity);
                if (changed) GameplayPackets.inventoryChanges(type, before,
                        GameplayPackets.inventory(character, type)).forEach(session::send);
            }
        } finally { session.send(GameplayPackets.enableActions()); }
    }
}
