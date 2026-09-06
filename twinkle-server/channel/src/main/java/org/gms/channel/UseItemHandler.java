package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ItemSystem;

import java.util.Map;

/** 普通恢复药入口；v83 字段顺序参考北斗协议行为，业务与封包实现独立编写。 */
public final class UseItemHandler implements PacketHandler {
    private final ItemSystem items;

    public UseItemHandler(ItemSystem items, GameDataProvider data) {
        this.items = items;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 10) return;
            packet.skip(4);
            short slot = packet.readShort();
            int itemId = packet.readInt();
            synchronized (character) {
                var before = GameplayPackets.inventory(character, InventoryType.USE);
                if (!items.consumeRecovery(character, slot, itemId, System.currentTimeMillis())) return;
                GameplayPackets.inventoryChanges(InventoryType.USE, before,
                        GameplayPackets.inventory(character, InventoryType.USE)).forEach(session::send);
                session.send(GameplayPackets.stats(Map.of(GameplayPackets.HP, character.getHp(),
                        GameplayPackets.MP, character.getMp())));
            }
        } finally {
            session.send(GameplayPackets.enableActions());
        }
    }
}
