package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.item.ItemData;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.replaceable.ItemSystem;

import java.util.Map;

/**
 * 物品使用（RecvOpcode.USE_ITEM）。
 *
 * <p>v83 收包 = {@code skip(4)} + {@code readShort(slot)} + {@code readInt(itemId)}
 * （布局思路参考自 BeiDou-Server 的 UseItemHandler，实现自研）。流程：
 * 校验 USE 栏槽位物品 → 经 {@link ItemSystem} 扣 1 → 应用 {@link ItemData.stats}
 * 效果（hp/mp 药加血加蓝，hpr/mpr 按百分比）→ 发 STAT_CHANGED。
 *
 * <p>handler 只做"收包→调 system→发包"；物品 id 校验、扣量在 system（版本门）。
 */
public final class UseItemHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(UseItemHandler.class);

    private final ItemSystem itemSystem;
    private final Map<Integer, ItemData> itemData;

    public UseItemHandler(ItemSystem itemSystem, Map<Integer, ItemData> itemData) {
        this.itemSystem = itemSystem;
        this.itemData = itemData;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到使用物品");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到使用物品");
            return;
        }
        packet.skip(4);
        short slot = packet.readShort();
        int itemId = packet.readInt();

        Item item = chr.getInventory(InventoryType.USE).getItem(slot);
        if (item == null || item.getId() != itemId) {
            return;     // 槽位/物品不匹配
        }
        if (!itemSystem.takeItem(chr, itemId, 1)) {
            return;     // 扣除失败（版本门/数量不足）
        }
        applyEffect(chr, itemId);
        session.send(statChanged(chr));
    }

    /** 应用 ItemData 效果（hp/mp 恢复，hpr/mpr 按 max 百分比，封顶）。 */
    private void applyEffect(Character chr, int itemId) {
        ItemData data = itemData.get(itemId);
        if (data == null) {
            return;
        }
        Integer hp = data.getStat("hp");
        if (hp != null && hp > 0) {
            chr.setHp(Math.min(chr.getMaxHp(), chr.getHp() + hp));
        }
        Integer mp = data.getStat("mp");
        if (mp != null && mp > 0) {
            chr.setMp(Math.min(chr.getMaxMp(), chr.getMp() + mp));
        }
        Integer hpr = data.getStat("hpr");
        if (hpr != null && hpr > 0) {
            chr.setHp(Math.min(chr.getMaxHp(), chr.getHp() + (chr.getMaxHp() * hpr / 100)));
        }
        Integer mpr = data.getStat("mpr");
        if (mpr != null && mpr > 0) {
            chr.setMp(Math.min(chr.getMaxMp(), chr.getMp() + (chr.getMaxMp() * mpr / 100)));
        }
    }

    /** 状态变化包（STAT_CHANGED）：固定结构，含 HP/MP。 */
    private static OutPacket statChanged(Character chr) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.STAT_CHANGED.getValue());
        p.writeInt(0);                  // 掩码占位（后续按位填 HP/MP 标志）
        p.writeInt(0);
        p.writeInt(chr.getHp());
        p.writeInt(chr.getMp());
        return p;
    }
}
