package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.domain.game.Character;
import org.gms.event.ReliableEventBus;
import org.gms.i18n.I18n;
import org.gms.message.ChangeChannelRequest;
import org.gms.message.MessageTargets;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.intercoord.IntercoordService;

/**
 * 换频道处理（RecvOpcode.CHANGE_CHANNEL 0x27，架构 4.7：一机制两用）。
 *
 * <p>功能性：玩家换频道 = 经消息总线发 CC 请求 → 定位表更新 → 老频道下线清 map → 目标频道
 * 注册 → 客户端重连（v83 换频道本来就是 loading 界面）。兜底性：升级前把玩家挪到别的频道
 * （MAINTENANCE reason）→ 重启 → 回来，玩家视角只是"换了一次频道"。
 *
 * <p>v83 收包：opcode(2) + 4B 头 + 目标频道（1B，0-based）。M6 跨进程：发送前<b>同步存档</b>
 * （玩家状态落 DB，目标频道重连后从 DB 加载最新态，不掉数据）；目标频道经
 * {@link ChannelChangeReceiver} 消费 CC 请求（恰好一次）。
 */
@Log4j2
public final class ChangeChannelHandler implements PacketHandler {



    private final int channelId;
    private final IntercoordService intercoord;
    private final ReliableEventBus reliableBus;
    private final PlayerSessionRegistry sessions;
    private final PlayerStorage players;
    private final CharacterSaveQueue saveQueue;

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions) {
        this(channelId, intercoord, reliableBus, sessions, null, null);
    }

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions, CharacterSaveQueue saveQueue) {
        this(channelId, intercoord, reliableBus, sessions, null, saveQueue);
    }

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions, PlayerStorage players,
                                CharacterSaveQueue saveQueue) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.reliableBus = reliableBus;
        this.sessions = sessions;
        this.players = players;
        this.saveQueue = saveQueue;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.channel.change.outside_stage"));
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.channel.change.not_in_map"));
            return;
        }

        // 解析：4B 头 + 1B 目标频道（0-based）
        if (packet.available() < 5) {
            return;
        }
        packet.skip(4);
        int targetChannel = packet.readByte() & 0xFF; // 0-based
        int targetId = targetChannel + 1;             // 频道 id（1-based）

        if (targetId == channelId) {
            return; // 同频道，忽略
        }

        // 发送前同步存档（架构 4.7：老频道 flush 状态 → 目标频道加载）。玩家状态落 DB，
        // 目标频道重连后 PlayerLoggedinHandler 从 DB 加载最新态（跨进程不掉数据）。
        if (saveQueue != null) {
            saveQueue.flushCharacterSync(chr);
        }

        // 发 CC 请求（经可靠总线发目标频道，架构 4.5：CC 迁移不掉数据、不重复的核心）。
        // 单一属主序号流：每玩家的 CC 请求流内单调，进程崩了重投未 ACKED。
        ChangeChannelRequest req = new ChangeChannelRequest(chr.getId(), channelId, targetId,
                ChangeChannelRequest.Reason.PLAYER_CHANGE);
        log.info(I18n.message("log.channel.change.request"), chr.getName(), channelId, targetId, req.reason());
        reliableBus.send("cc:player:" + chr.getId(), MessageTargets.channel(targetId), req);

        // 开始迁移：旧频道仍是 TCP 属主；目标频道只有在客户端重连并完成 PlayerLoggedin 后
        // 才能登记为新属主，避免“尚未连接目标频道但定位已过去”的幽灵在线。
        intercoord.beginChannelTransfer(chr.getId(), channelId, targetId);
        if (chr.getMapObject() != null) {
            chr.getMapObject().removeCharacter(chr);
        }
        if (players != null) {
            players.remove(chr);
        }
        sessions.unregister(chr.getId(), session);
        session.transition(SessionStage.CHANNEL_TRANSITION);
        // 玩家重连目标频道端口 → PlayerLoggedinHandler 重新进图（v83 loading 界面）
        log.info(I18n.message("log.channel.change.complete"), chr.getName(), targetId, targetId);
    }
}
