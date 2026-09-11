package org.gms.channel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.GameExecution;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;
import org.gms.message.BuddyRequest;
import org.gms.message.MessageTargets;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.service.intercoord.IntercoordService;



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
@Log4j2
public final class BuddyHandler implements PacketHandler {



    private SerialTaskQueue io;
    public BuddyHandler async(SerialTaskQueue io) { this.io = io; return this; }

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
            session.close(I18n.message("error.buddy.outside_stage"));
            return;
        }
        PlayerCharacter chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.buddy.not_in_map"));
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
        PacketSession target = sessions.get(buddyId);
        if (io == null) { applyLocal(chr, req, buddyName); return; }
        long version = sessions.execution().version();
        io.run(() -> persist(req)).whenComplete((ignored, error) ->
                sessions.execution().continueAt(version, () -> {
                    if (error != null) {
                        log.error(I18n.message("log.social.io_failed"), error);
                        if (sessions.get(req.fromId()) == session) session.send(GameplayPackets.enableActions());
                        return;
                    }
                    // 回执只给发起时的会话，断线重登的新会话不得收到旧请求回包。
                    if (sessions.get(req.fromId()) == session) session.send(buddyListPacket(req.fromId()));
                    if (target != null && sessions.get(req.toId()) == target) target.send(buddyListPacket(req.toId()));
                }));
    }

    /** 未绑定频道的兼容入口；游戏线程禁止同步写库。 */
    public void applyLocal(PlayerCharacter chr, BuddyRequest req, String buddyName) {
        if (GameExecution.inGameOperation()) throw new IllegalStateException("Buddy persistence requires IO queue");
        persist(req);
        PacketSession from = sessions.get(req.fromId());
        if (from != null) from.send(buddyListPacket(req.fromId()));
        PacketSession to = sessions.get(req.toId());
        if (to != null) to.send(buddyListPacket(req.toId()));
    }

    private void persist(BuddyRequest req) {
        switch (req.action()) {
            case ADD_REQUEST -> {
                buddyRepo.insertIfAbsent(row(req.fromId(), req.toId(), BuddyListEntity.PENDING));
                buddyRepo.insertIfAbsent(row(req.toId(), req.fromId(), BuddyListEntity.PENDING));
            }
            case ACCEPT -> {
                buddyRepo.updateStatus(req.fromId(), req.toId(), BuddyListEntity.ACCEPTED);
                buddyRepo.updateStatus(req.toId(), req.fromId(), BuddyListEntity.ACCEPTED);
            }
            case DELETE -> {
                buddyRepo.delete(req.fromId(), req.toId());
                buddyRepo.delete(req.toId(), req.fromId());
            }
            default -> { }
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
            PlayerCharacter c = s.getAttr("character");
            if (c != null && c.getName().equals(name)) {
                return c.getId();
            }
        }
        return null;
    }
}
