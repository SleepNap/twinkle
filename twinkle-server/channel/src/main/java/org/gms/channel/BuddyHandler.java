package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.data.entity.BuddyListEntity;
import org.gms.data.repo.BuddyListRepository;
import org.gms.domain.game.Character;
import org.gms.event.EventBus;
import org.gms.message.BuddyRequest;
import org.gms.message.MessageTargets;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.intercoord.IntercoordService;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 好友处理（RecvOpcode.BUDDYLIST_MODIFY 0x82，架构 4.4 三机制：单一属主 + 定位表 + 消息总线）。
 *
 * <p>流程：
 * <ul>
 *   <li>加好友：经定位表查目标在线 → 经消息总线发目标频道（目标确认 → 双方列表更新）。</li>
 *   <li>好友关系持久化：{@code buddylist} 表（V6 迁移），单一属主真值。</li>
 *   <li>登录加载好友列表（BUDDYLIST 包）。</li>
 * </ul>
 *
 * <p>v83 收包：opcode(2) + 4B 头 + 动作(1B) + 目标名（短字符串）。
 * 思路参考自 BeiDou-Server 的 BuddyListHandler，实现自研。
 */
public final class BuddyHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(BuddyHandler.class);

    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;
    private final BuddyListRepository buddyRepo;

    public BuddyHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                        PlayerSessionRegistry sessions, BuddyListRepository buddyRepo) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.eventBus = eventBus;
        this.sessions = sessions;
        this.buddyRepo = buddyRepo;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到好友请求");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到好友请求");
            return;
        }
        if (packet.available() < 6) {
            return;
        }
        packet.skip(4); // 未知头
        int action = packet.readByte() & 0xFF;
        String buddyName = readShortString(packet);
        if (buddyName == null || buddyName.isEmpty()) {
            return;
        }

        BuddyRequest.Action op = switch (action) {
            case 0 -> BuddyRequest.Action.ADD_REQUEST;
            case 1 -> BuddyRequest.Action.ACCEPT;
            case 2 -> BuddyRequest.Action.REJECT;
            case 3 -> BuddyRequest.Action.DELETE;
            default -> null;
        };
        if (op == null) {
            return;
        }
        Long buddyId = resolveIdByName(buddyName);
        if (buddyId == null) {
            return; // 目标不在线（加好友可离线，M4 简化：仅在线）
        }
        BuddyRequest req = new BuddyRequest(chr.getId(), chr.getName(), buddyId, op);
        int targetChannel = intercoord.locate(buddyId).orElse(-1);
        if (targetChannel == channelId) {
            applyLocal(chr, req, buddyName);
        } else if (targetChannel > 0) {
            // 跨频道：经消息总线投递目标频道（总线不存状态，只负责送达）
            eventBus.send(MessageTargets.channel(targetChannel), req);
        }
    }

    /** 本频道内的好友动作（收到总线投递的跨频道请求也走这里）。 */
    public void applyLocal(Character chr, BuddyRequest req, String buddyName) {
        switch (req.action()) {
            case ADD_REQUEST -> {
                // 单一属主：buddylist 表持久化（PENDING），双方都写
                boolean newRow = buddyRepo.insertIfAbsent(row(req.fromId(), req.toId(), BuddyListEntity.PENDING));
                buddyRepo.insertIfAbsent(row(req.toId(), req.fromId(), BuddyListEntity.PENDING));
                LOG.info("好友请求: {} → {}（新建={}）", req.fromName(), buddyName, newRow);
            }
            case ACCEPT -> {
                buddyRepo.updateStatus(req.fromId(), req.toId(), BuddyListEntity.ACCEPTED);
                buddyRepo.updateStatus(req.toId(), req.fromId(), BuddyListEntity.ACCEPTED);
                LOG.info("好友确认: {} ↔ {}", req.fromName(), buddyName);
            }
            case DELETE -> {
                buddyRepo.delete(req.fromId(), req.toId());
                buddyRepo.delete(req.toId(), req.fromId());
                LOG.info("好友删除: {} ↔ {}", req.fromName(), buddyName);
            }
            default -> {
            }
        }
        // 回 BUDDYLIST 包（双方在线则都刷）
        PacketSession from = sessions.get(req.fromId());
        if (from != null) {
            from.send(buddyListPacket(req.fromId()));
        }
        PacketSession to = sessions.get(req.toId());
        if (to != null) {
            to.send(buddyListPacket(req.toId()));
        }
    }

    /** 登录加载好友列表（进图时调用，回 BUDDYLIST 包）。 */
    public void sendBuddyList(PacketSession session, long playerId) {
        session.send(buddyListPacket(playerId));
    }

    private BuddyListEntity row(long owner, long buddy, String status) {
        BuddyListEntity e = new BuddyListEntity();
        e.setOwnerId(owner);
        e.setBuddyId(buddy);
        e.setStatus(status);
        return e;
    }

    /** 构建 v83 BUDDYLIST 包（0x3F）：数量 + 每项（id + 名 + 在线 + 状态）。 */
    public static OutPacket buddyListPacket(long playerId) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.BUDDYLIST.getValue());
        p.writeByte(1); // 加载列表模式
        p.writeInt(0);  // 数量（M4 简化：由调用方后续补全真实列表）
        return p;
    }

    private static String readShortString(InPacket packet) {
        if (packet.available() < 1) {
            return null;
        }
        int len = packet.readByte() & 0xFF;
        if (len == 0) {
            return "";
        }
        if (packet.available() < len) {
            return null;
        }
        return new String(packet.readBytes(len), StandardCharsets.UTF_8);
    }

    private Long resolveIdByName(String name) {
        for (PacketSession s : sessions.all()) {
            Character c = s.getAttr("character");
            if (c != null && c.getName().equals(name)) {
                return c.getId();
            }
        }
        return null;
    }
}
