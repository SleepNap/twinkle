package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.map.MapNpc;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ItemSystem;

import java.util.List;
import java.util.Map;

/** NPC 商店开窗与买卖；会话绑定地图和真实 NPC 对象，换图后旧报价失效。 */
public final class NpcShopHandler implements PacketHandler {
    public record ShopSession(int mapId, int objectId, List<NpcShopCatalog.Offer> offers) { }
    private final NpcShopCatalog catalog;
    private final GameDataProvider data;
    private final ItemSystem items;

    public NpcShopHandler(NpcShopCatalog catalog, GameDataProvider data, ItemSystem items) {
        this.catalog = catalog;
        this.data = data;
        this.items = items;
    }

    public boolean open(PacketSession session, MapNpc npc) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character) || !GameplaySession.near(character, npc.x(), npc.y(), 300))
            return false;
        List<NpcShopCatalog.Offer> offers = catalog.offers(npc.templateId(), data);
        if (offers.isEmpty()) return false;
        session.setAttr("npcShop", new ShopSession(character.getMap(), npc.objectId(), offers));
        var packet = GameplayPackets.packet(SendOpcode.OPEN_NPC_SHOP);
        packet.writeInt(npc.templateId());
        packet.writeShort(offers.size());
        for (var offer : offers) {
            packet.writeInt(offer.itemId());
            packet.writeInt(offer.price());
            packet.writeInt(0);
            packet.writeInt(0);
            packet.writeInt(0);
            packet.writeShort(1);
            packet.writeShort(Math.max(1, Math.min(Short.MAX_VALUE, data.item(offer.itemId()).getSlotMax())));
        }
        session.send(packet);
        return true;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        ItemSystem.ShopResult result = ItemSystem.ShopResult.INVALID;
        boolean closed = false;
        try {
            PlayerCharacter character = GameplaySession.character(session);
            ShopSession shop = session.getAttr("npcShop");
            if (packet.available() < 1) return;
            int mode = packet.readByte();
            if (mode == 3) { session.setAttr("npcShop", null); closed = true; return; }
            if (!GameplaySession.canAct(session, character) || shop == null || shop.mapId() != character.getMap()
                    || packet.available() < 8) return;
            MapNpc npc = character.getMapObject().getNpc(shop.objectId());
            if (npc == null || !GameplaySession.near(character, npc.x(), npc.y(), 300)) return;
            short slot = packet.readShort();
            int itemId = packet.readInt(), quantity = packet.readShort();
            var type = ItemConstants.getInventoryType(itemId);
            if (type == InventoryType.UNDEFINED) return;
            var before = GameplayPackets.inventory(character, type);
            if (mode == 0 && slot >= 0 && slot < shop.offers().size()) {
                var offer = shop.offers().get(slot);
                if (offer.itemId() == itemId) result = items.buy(character, itemId, quantity, offer.price());
            } else if (mode == 1) result = items.sell(character, type.getType(), slot, itemId, quantity);
            if (result == ItemSystem.ShopResult.SUCCESS) {
                GameplayPackets.inventoryChanges(type, before, GameplayPackets.inventory(character, type)).forEach(session::send);
                session.send(GameplayPackets.stats(Map.of(GameplayPackets.MESO, character.getMeso())));
            }
        } finally {
            var reply = GameplayPackets.packet(SendOpcode.CONFIRM_SHOP_TRANSACTION);
            reply.writeByte(switch (result) { case SUCCESS -> 0; case NO_MONEY -> 2; case NO_SPACE -> 3; default -> 6; });
            if (!closed) session.send(reply);
            session.send(GameplayPackets.enableActions());
        }
    }
}
