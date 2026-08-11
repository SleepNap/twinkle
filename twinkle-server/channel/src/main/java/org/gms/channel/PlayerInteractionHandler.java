package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.net.packet.v83.V83CharacterLook;
import org.gms.net.packet.v83.V83CharacterPacketWriter;
import org.gms.net.packet.v83.V83EquippedItem;
import org.gms.net.packet.v83.V83ItemPacketWriter;
import org.gms.replaceable.TradeSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家互动处理（RecvOpcode.PLAYER_INTERACTION）——交易。
 *
 * <p>v83 交易全部走 PLAYER_INTERACTION（收 0x7B / 发 0x13A），首字节为子动作
 * （CREATE=0/INVITE=2/DECLINE=3/VISIT=4/ROOM=5/EXIT=0xA/SET_ITEMS=0xF/
 * SET_MESO=0x10/CONFIRM=0x11）。布局思路参考自 BeiDou-Server 的
 * PlayerInteractionHandler + PacketCreator，实现自研。
 *
 * <p>状态与结算经 {@link TradeSystem}（版本门 + 双向结算）；交易实例存双方 session
 * attr（连接级状态，不跨操作）。handler 只做收包→调 system→发包。
 *
 * <p>SET_ITEMS 按槽位取物品并用公共 v83 物品写入器广播；加物品不校验槽位重复
 * （take 时按 offer 累计扣）。
 */
@Log4j2
public final class PlayerInteractionHandler implements PacketHandler {



    /** 交易操作码（收/发共用子动作值）。 */
    private static final int ACTION_CREATE = 0x00;
    private static final int ACTION_INVITE = 0x02;
    private static final int ACTION_DECLINE = 0x03;
    private static final int ACTION_VISIT = 0x04;
    private static final int ACTION_EXIT = 0x0A;
    private static final int ACTION_SET_ITEMS = 0x0F;
    private static final int ACTION_SET_MESO = 0x10;
    private static final int ACTION_CONFIRM = 0x11;

    /** 交易结果（getTradeResult 的 operation）。 */
    private static final int RESULT_PARTNER_CANCEL = 2;
    private static final int RESULT_SUCCESS = 7;
    private static final int RESULT_UNSUCCESSFUL = 8;

    /** 会话 attr key。 */
    private static final String TRADE_ATTR = "trade";

    private final TradeSystem tradeSystem;
    private final PlayerSessionRegistry sessions;
    private final org.gms.hotreload.EntityReloadCoordinator reloadCoordinator;

    public PlayerInteractionHandler(TradeSystem tradeSystem, PlayerSessionRegistry sessions) {
        this(tradeSystem, sessions, null);
    }

    public PlayerInteractionHandler(TradeSystem tradeSystem, PlayerSessionRegistry sessions,
                                    org.gms.hotreload.EntityReloadCoordinator reloadCoordinator) {
        this.tradeSystem = tradeSystem;
        this.sessions = sessions;
        this.reloadCoordinator = reloadCoordinator;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到互动包");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到互动包");
            return;
        }
        int action = packet.readByte();
        switch (action) {
            case ACTION_CREATE -> create(session, chr);
            case ACTION_INVITE -> invite(session, chr, packet);
            case ACTION_DECLINE -> decline(session, chr);
            case ACTION_VISIT -> visit(session, chr);
            case ACTION_EXIT -> exit(session, chr);
            case ACTION_SET_ITEMS -> setItems(session, chr, packet);
            case ACTION_SET_MESO -> setMeso(session, chr, packet);
            case ACTION_CONFIRM -> confirm(session, chr);
            default -> log.debug("未处理的互动动作: {}", action);
        }
    }

    /* ---------- 子动作 ---------- */

    private void create(PacketSession session, Character chr) {
        // CREATE 后客户端再发 INVITE 指定目标；此处不建交易（等待 INVITE 带目标）
    }

    private void invite(PacketSession session, Character chr, InPacket packet) {
        long targetId = packet.readInt();
        PacketSession target = sessions.get(targetId);
        if (target == null || target == session) {
            return;
        }
        Character targetChr = target.getAttr("character");
        if (targetChr == null) {
            return;
        }
        // 建交易：双方各持同一实例（存各自会话 attr）
        TradeSide first = new TradeSide(chr);
        TradeSide second = new TradeSide(targetChr);
        Trade trade = tradeSystem.create(first, second);
        session.setAttr(TRADE_ATTR, trade);
        target.setAttr(TRADE_ATTR, trade);
        // 长操作跟踪（架构 5.3：交易跨 tick，重载安全点判定用）
        if (reloadCoordinator != null) {
            reloadCoordinator.beginOperation(chr.getId());
            reloadCoordinator.beginOperation(targetChr.getId());
        }
        // 邀请包（0x13A, INVITE + 3 + 名字 + 4B）
        target.send(tradeInvite(chr));
    }

    private void decline(PacketSession session, Character chr) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        // 通知对方取消
        TradeSide other = trade.sideOf(chr) == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        PacketSession partnerSession = sessions.get(other.getTrader().getId());
        if (partnerSession != null) {
            partnerSession.send(tradeResult(number(partnerSession, trade), (byte) RESULT_PARTNER_CANCEL));
        }
        clearTrade(session);
        endOperations(trade);
    }

    private void visit(PacketSession session, Character chr) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        // 对方接受：给发起方发"对方已进房"，双方进窗口
        TradeSide my = trade.sideOf(chr);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        PacketSession partnerSession = sessions.get(partner.getTrader().getId());
        if (partnerSession != null) {
            partnerSession.send(tradePartnerAdd(chr));
            partnerSession.send(tradeStart(partnerSession, trade, number(partnerSession, trade)));
        }
        session.send(tradeStart(session, trade, number(session, trade)));
    }

    private void exit(PacketSession session, Character chr) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        TradeSide my = trade.sideOf(chr);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        PacketSession partnerSession = sessions.get(partner.getTrader().getId());
        if (partnerSession != null) {
            partnerSession.send(tradeResult(number(partnerSession, trade), (byte) RESULT_PARTNER_CANCEL));
        }
        session.send(tradeResult(number(session, trade), (byte) RESULT_PARTNER_CANCEL));
        clearTrade(session);
        if (partnerSession != null) {
            clearTrade(partnerSession);
        }
        endOperations(trade);
    }

    private void setItems(PacketSession session, Character chr, InPacket packet) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        byte inventoryType = packet.readByte();
        short pos = packet.readShort();
        short quantity = packet.readShort();
        byte targetSlot = packet.readByte();

        Item item = chr.getInventory(InventoryType.getByType(inventoryType)).getItem(pos);
        if (item == null) {
            return;
        }
        // 经 TradeSystem.offer（版本门 + 原槽位精确实例校验）
        if (!tradeSystem.offer(trade, chr, inventoryType, pos, quantity, targetSlot)) {
            return;
        }
        // 广播完整物品段；目标槽位由客户端交易窗口分配
        TradeSide my = trade.sideOf(chr);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        OutPacket add = tradeItemAdd(number(session, trade), targetSlot, item, quantity);
        session.send(add);
        PacketSession partnerSession = sessions.get(partner.getTrader().getId());
        if (partnerSession != null) {
            partnerSession.send(add);
        }
    }

    private void setMeso(PacketSession session, Character chr, InPacket packet) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        int meso = packet.readInt();
        if (!tradeSystem.offerMeso(trade, chr, meso)) {
            return;
        }
        TradeSide my = trade.sideOf(chr);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        OutPacket set = tradeMesoSet(number(session, trade), meso);
        session.send(set);
        PacketSession partnerSession = sessions.get(partner.getTrader().getId());
        if (partnerSession != null) {
            partnerSession.send(set);
        }
    }

    private void confirm(PacketSession session, Character chr) {
        Trade trade = session.getAttr(TRADE_ATTR);
        if (trade == null) {
            return;
        }
        TradeSide my = trade.sideOf(chr);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        PacketSession partnerSession = sessions.get(partner.getTrader().getId());
        TradeSystem.ConfirmResult result = tradeSystem.confirm(trade, chr);
        if (result == TradeSystem.ConfirmResult.COMPLETED || result == TradeSystem.ConfirmResult.FAILED) {
            byte operation = result == TradeSystem.ConfirmResult.COMPLETED
                    ? (byte) RESULT_SUCCESS : (byte) RESULT_UNSUCCESSFUL;
            session.send(tradeResult(number(session, trade), operation));
            if (partnerSession != null) {
                partnerSession.send(tradeResult(number(partnerSession, trade), operation));
            }
            clearTrade(session);
            if (partnerSession != null) {
                clearTrade(partnerSession);
            }
            endOperations(trade);
        } else if (result == TradeSystem.ConfirmResult.WAITING) {
            session.send(tradeConfirmation());
            if (partnerSession != null) {
                partnerSession.send(tradeConfirmation());
            }
        }
    }

    /* ---------- 发包 ---------- */

    /** 邀请：byte INVITE + 3 + string 名字 + 4B。 */
    private static OutPacket tradeInvite(Character chr) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_INVITE);
        p.writeByte(3);
        p.writeString(chr.getName());
        p.writeBytes(new byte[]{(byte) 0xB7, 0x50, 0, 0});
        return p;
    }

    /** 对方进房：byte VISIT + 1 + addCharLook + string 名字。 */
    private static OutPacket tradePartnerAdd(Character chr) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_VISIT);
        p.writeByte(1);
        V83CharacterPacketWriter.writeLook(p, toProtocolLook(chr), false);
        p.writeString(chr.getName());
        return p;
    }

    /** 进窗口：byte ROOM + 3 + 2 + number + [number==1 对方] + 本方 + 0xFF。 */
    private static OutPacket tradeStart(PacketSession session, Trade trade, byte number) {
        Character me = session.getAttr("character");
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(5);                     // ROOM
        p.writeByte(3);
        p.writeByte(2);
        p.writeByte(number);
        if (number == 1) {
            Character partner = partnerOf(session, trade);
            p.writeByte(0);
            V83CharacterPacketWriter.writeLook(p, toProtocolLook(partner), false);
            p.writeString(partner.getName());
        }
        p.writeByte(number);
        V83CharacterPacketWriter.writeLook(p, toProtocolLook(me), false);
        p.writeString(me.getName());
        p.writeByte(0xFF);
        return p;
    }

    /** 加物品：byte SET_ITEMS + number + targetSlot + 完整 addItemInfo（不重复写背包槽位）。 */
    private static OutPacket tradeItemAdd(byte number, byte targetSlot, Item item, int quantity) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_SET_ITEMS);
        p.writeByte(number);
        p.writeByte(targetSlot);
        V83ItemPacketWriter.write(p, ChannelItemProtocolMapper.toSnapshot(item, quantity), false);
        return p;
    }

    /** 加金币：byte SET_MESO + number + int meso。 */
    private static OutPacket tradeMesoSet(byte number, int meso) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_SET_MESO);
        p.writeByte(number);
        p.writeInt(meso);
        return p;
    }

    /** 锁定确认：byte CONFIRM。 */
    private static OutPacket tradeConfirmation() {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_CONFIRM);
        return p;
    }

    /** 结果：byte EXIT + number + operation。 */
    private static OutPacket tradeResult(byte number, byte operation) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(ACTION_EXIT);
        p.writeByte(number);
        p.writeByte(operation);
        return p;
    }

    /* ---------- 工具 ---------- */

    /** 本会话在交易里的编号（0=发起方/1=接受方，按 TradeSide 首尾判定）。 */
    private static byte number(PacketSession session, Trade trade) {
        Character me = session.getAttr("character");
        return trade.sideOf(me) == trade.getFirst() ? (byte) 0 : (byte) 1;
    }

    private static Character partnerOf(PacketSession session, Trade trade) {
        Character me = session.getAttr("character");
        TradeSide my = trade.sideOf(me);
        TradeSide partner = my == trade.getFirst() ? trade.getSecond() : trade.getFirst();
        return (Character) partner.getTrader();
    }

    private static V83CharacterLook toProtocolLook(Character chr) {
        List<V83EquippedItem> equipped = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIP).items()) {
            if (item.getPosition() < 0) {
                equipped.add(new V83EquippedItem(item.getPosition(), item.getId()));
            }
        }
        return new V83CharacterLook(chr.getGender(), chr.getSkinColor(), chr.getFace(), chr.getHair(), equipped);
    }

    private static void clearTrade(PacketSession session) {
        session.setAttr(TRADE_ATTR, null);
    }

    /** 交易结束/中断：释放双方长操作跟踪（回到安全点，可重载）。 */
    private void endOperations(Trade trade) {
        if (reloadCoordinator == null) {
            return;
        }
        Character first = (Character) trade.getFirst().getTrader();
        Character second = (Character) trade.getSecond().getTrader();
        reloadCoordinator.endOperation(first.getId());
        reloadCoordinator.endOperation(second.getId());
    }
}
