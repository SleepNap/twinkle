package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;
import org.gms.message.MessageTargets;
import org.gms.message.WhisperRequest;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.intercoord.IntercoordService;

/**
 * 悄悄话处理（RecvOpcode.WHISPER 0x78，架构 4.4 消息总线：悄悄话=发目标频道）。
 *
 * <p>流程：解析目标角色名 + 内容 → 定位表查"目标在哪个频道" →
 * 经消息总线投递目标频道 → 目标会话回 WHISPER 包。同频道直发（不经总线）。
 *
 * <p>v83 收包：opcode(2) + 4B 未知头 + 目标名（短字符串） + 内容（短字符串）。
 * 布局思路参考自 BeiDou-Server 的 WhisperHandler，实现自研。
 */
@Log4j2
public final class WhisperHandler implements PacketHandler {



    /** 本频道 id（进图装配传入，M4 单频道多为 1）。 */
    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;

    public WhisperHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                          PlayerSessionRegistry sessions) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.eventBus = eventBus;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.whisper.outside_stage"));
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.whisper.not_in_map"));
            return;
        }

        // 解析：4B 头 + 目标名（短字符串 = 1B 长度 + utf8）+ 内容（短字符串）
        if (packet.available() < 5) {
            return; // 空包丢弃
        }
        packet.skip(4);
        String toName = readShortString(packet);
        String content = readShortString(packet);
        if (toName == null || content == null || toName.isEmpty() || content.isEmpty()) {
            return;
        }

        // 定位表查目标频道
        Long toId = resolvePlayerIdByName(toName);
        if (toId == null) {
            sendWhisperResult(session, toName, I18n.message("game.whisper.target_offline"));
            return;
        }
        int toChannel = intercoord.locate(toId).orElse(-1);
        WhisperRequest req = new WhisperRequest(chr.getId(), chr.getName(), toId, toName, content);

        if (toChannel == channelId) {
            // 同频道直发（不经总线）
            PacketSession target = sessions.get(toId);
            if (target != null) {
                target.send(whisperPacket(req));
            } else {
                sendWhisperResult(session, toName, I18n.message("game.whisper.target_offline"));
            }
        } else if (toChannel > 0) {
            // 跨频道：经消息总线投递目标频道（总线不存状态，只负责送达）
            eventBus.send(MessageTargets.channel(toChannel), req);
        } else {
            sendWhisperResult(session, toName, I18n.message("game.whisper.target_offline"));
        }
    }

    /** 构建 v83 WHISPER 回包（0x87）：1 未读 + 发送者名（短字符串） + 频道(1B) + 内容（短字符串）。 */
    public static OutPacket whisperPacket(WhisperRequest req) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.WHISPER.getValue());
        p.writeByte(1); // 1 = 收到悄悄话
        writeShortString(p, req.fromName());
        p.writeByte(3); // 频道展示（v83 客户端 1-based；M4 简化固定，M5 校正）
        writeShortString(p, req.content());
        return p;
    }

    /** 发送方侧回执（对方不在线/不可达）。 */
    private static void sendWhisperResult(PacketSession session, String toName, String message) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.WHISPER.getValue());
        p.writeByte(0); // 0 = 未投递
        writeShortString(p, toName);
        p.writeByte(0);
        writeShortString(p, message);
        session.send(p);
    }

    /** 读 1 字节长度字符串（v83 悄悄话用短字符串；包接口默认 short 长度，此处手动短读）。 */
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
        byte[] bytes = packet.readBytes(len);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeShortString(ByteArrayOutPacket p, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        p.writeByte(bytes.length);
        p.writeBytes(bytes);
    }

    /** 按角色名解析 id：先查本频道会话表（M4 单频道：在线玩家都在本频道会话表）。 */
    private Long resolvePlayerIdByName(String name) {
        for (PacketSession s : sessions.all()) {
            Character c = s.getAttr("character");
            if (c != null && c.getName().equals(name)) {
                return c.getId();
            }
        }
        return null;
    }
}
