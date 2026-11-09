package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
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
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.service.intercoord.IntercoordService;
import org.gms.service.intercoord.PlayerPresenceService.PlayerPresence;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 悄悄话处理（RecvOpcode.WHISPER 0x78；发包 SendOpcode.WHISPER）。
 *
 * <p>v83 收包（以北斗客户端协议为准）：flags(1B) + 目标名（Maple 字符串）；
 * flags 含 WHISPER 时再读内容（Maple 字符串），含 LOCATION/LOCATION_FRIEND 时是查询位置。
 * 旧实现按“4 字节头 + 1 字节长度字符串”解析，与 v83 不一致，本批修正。
 *
 * <p>发包：
 * <ul>
 *   <li>收到悄悄话：{@code WHISPER|RECEIVE} + 发送者名 + 频道(1B, channel-1) + 是否 GM + 内容。</li>
 *   <li>发送结果：{@code WHISPER|RESULT} + 目标名 + 成功(bool)。</li>
 *   <li>位置结果：{@code flags|RESULT} + 名字 + 类型 + 地图/频道；同频道再补 x/y。</li>
 * </ul>
 *
 * <p>目标跨频道投递：先查本频道会话，再经角色名查角色库并用大区 Presence 定位属主频道，
 * 最后按 {@code channel:{id}} 投递给目标频道（消息总线），由对方频道回包。
 */
@Log4j2
public final class WhisperHandler implements PacketHandler {
    /** v83 悄悄话标志位（与客户端 CBuddyList/CField::OnWhisper 的 flags 组合一致）。 */
    private static final int LOCATION = 0x01;
    private static final int WHISPER = 0x02;
    private static final int RESULT = 0x08;
    private static final int RECEIVE = 0x10;
    private static final int LOCATION_FRIEND = 0x40;

    /** 位置结果类型：0 现金道具/ITC、1 同频道、2 商城、3 其他频道。 */
    private static final int RT_SAME_CHANNEL = 1;
    private static final int RT_DIFFERENT_CHANNEL = 3;
    private static final int MAX_MESSAGE_LENGTH = 127;

    /** 解析目标：id + 属主频道（1 起）。 */
    private record Target(long id, int channel) { }

    private SerialTaskQueue io;
    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;
    private final PlayerCharacterRepository characters;

    public WhisperHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                          PlayerSessionRegistry sessions, PlayerCharacterRepository characters) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.eventBus = eventBus;
        this.sessions = sessions;
        this.characters = characters;
    }

    public WhisperHandler async(SerialTaskQueue io) {
        this.io = io;
        return this;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.whisper.outside_stage"));
            return;
        }
        PlayerCharacter chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.whisper.not_in_map"));
            return;
        }
        if (packet.available() < 1) return;
        int flags = packet.readByte() & 0xFF;
        if (packet.available() < 2) return;
        String targetName = packet.readString();
        if (targetName == null || targetName.isBlank()) return;

        if ((flags & WHISPER) != 0) {
            if (packet.available() < 2) return;
            String content = packet.readString();
            if (content == null || content.isEmpty()) return;
            if (content.length() > MAX_MESSAGE_LENGTH) {
                // 客户端正常不会超过 127 字符；超长直接丢弃，不按包编辑断线处理。
                log.debug(I18n.message("log.whisper.too_long"), chr.getName(), content.length());
                return;
            }
            whisper(session, chr, targetName, content);
            return;
        }
        if ((flags & (LOCATION | LOCATION_FRIEND)) != 0) {
            find(session, chr, targetName, (flags & LOCATION_FRIEND) != 0);
        }
    }

    private void whisper(PacketSession session, PlayerCharacter chr, String targetName, String content) {
        long fromId = chr.getId();
        String fromName = chr.getName();
        int fromChannel = channelId;
        offload(() -> resolve(targetName), target -> {
            if (sessions.get(fromId) != session) return;
            if (target == null) {
                session.send(whisperResult(targetName, false));
                return;
            }
            WhisperRequest request = new WhisperRequest(fromId, fromName, fromChannel,
                    target.id(), targetName, content);
            PacketSession local = sessions.get(target.id());
            if (local != null) {
                local.send(whisperPacket(request));
            } else if (target.channel() != channelId && eventBus != null) {
                eventBus.send(MessageTargets.channel(target.channel()), request);
            } else {
                session.send(whisperResult(targetName, false));
                return;
            }
            session.send(whisperResult(targetName, true));
        });
    }

    private void find(PacketSession session, PlayerCharacter chr, String targetName, boolean friend) {
        long fromId = chr.getId();
        byte flag = (byte) (friend ? LOCATION_FRIEND : LOCATION);
        offload(() -> resolve(targetName), target -> {
            if (sessions.get(fromId) != session) return;
            if (target == null) {
                session.send(whisperResult(targetName, false));
                return;
            }
            if (target.channel() != channelId) {
                session.send(findResult(flag, targetName, RT_DIFFERENT_CHANNEL,
                        target.channel() - 1, 0, 0));
                return;
            }
            PacketSession local = sessions.get(target.id());
            PlayerCharacter found = local == null ? null : local.getAttr("character");
            if (found == null) {
                session.send(whisperResult(targetName, false));
                return;
            }
            session.send(findResult(flag, targetName, RT_SAME_CHANNEL,
                    found.getMap(), found.getX(), found.getY()));
        });
    }

    /**
     * 按角色名解析目标：本频道会话优先（名字变更/在线状态以会话为准），
     * 其余走角色库 + 大区 Presence；Presence 查不到视为离线。
     */
    private Target resolve(String name) {
        for (PacketSession session : sessions.all()) {
            PlayerCharacter character = session.getAttr("character");
            if (character != null && character.getName().equals(name)) {
                return new Target(character.getId(), channelId);
            }
        }
        if (characters == null) return null;
        Optional<PlayerCharacterRecord> record = characters.findByName(name);
        if (record.isEmpty()) return null;
        long id = record.get().getId();
        int channel = channelOf(id);
        return channel > 0 ? new Target(id, channel) : null;
    }

    private int channelOf(long characterId) {
        if (sessions.get(characterId) != null) return channelId;
        if (intercoord == null) return -1;
        try {
            Optional<PlayerPresence> presence = intercoord.presence(characterId);
            return presence == null ? -1 : presence.map(PlayerPresence::ownerChannelId).orElse(-1);
        } catch (RuntimeException error) {
            log.debug(I18n.message("log.whisper.presence_failed"), characterId, error);
            return -1;
        }
    }

    /** 有执行归属时走后台 IO + 版本门回包；否则同步执行（测试/无频道适配器）。 */
    private <T> void offload(Supplier<T> task, Consumer<T> completion) {
        if (io == null || sessions.execution() == null) {
            completion.accept(task.get());
            return;
        }
        long version = sessions.execution().version();
        io.submit(task).whenComplete((result, error) -> sessions.execution().continueAt(version, () -> {
            if (error != null) {
                log.error(I18n.message("log.social.io_failed"), error);
                return;
            }
            completion.accept(result);
        }));
    }

    /**
     * 收到悄悄话（send）：{@code WHISPER|RECEIVE}、发送者名（Maple 字符串）、频道(1B)、
     * 是否管理员(bool)、内容（Maple 字符串）。
     */
    public static OutPacket whisperPacket(WhisperRequest req) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.WHISPER.getValue());
        packet.writeByte(WHISPER | RECEIVE);
        packet.writeString(req.fromName());
        packet.writeByte(Math.max(0, req.fromChannel() - 1));
        packet.writeBool(false);
        packet.writeString(req.content());
        return packet;
    }

    /** 发送结果（send）：{@code WHISPER|RESULT}、目标名、是否送达(bool)。 */
    private static OutPacket whisperResult(String targetName, boolean success) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.WHISPER.getValue());
        packet.writeByte(WHISPER | RESULT);
        packet.writeString(targetName);
        packet.writeBool(success);
        return packet;
    }

    /** 位置结果（send）：{@code flags|RESULT}、名字、类型、地图 id 或频道号；同频道再补 x/y。 */
    private static OutPacket findResult(byte flag, String targetName, int type,
                                        int fieldOrChannel, int x, int y) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.WHISPER.getValue());
        packet.writeByte(flag | RESULT);
        packet.writeString(targetName);
        packet.writeByte(type);
        packet.writeInt(fieldOrChannel);
        if (type == RT_SAME_CHANNEL) {
            packet.writeInt(x);
            packet.writeInt(y);
        }
        return packet;
    }
}
