package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.game.Character;
import org.gms.event.EventBus;
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
 * <p>v83 收包：opcode(2) + 4B 头 + 目标频道（1B，0-based）。M4 单进程内：目标频道 = 本进程，
 * 定位表 move + 清地图 + 重新登记即完成"迁移"。
 */
public final class ChangeChannelHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(ChangeChannelHandler.class);

    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                                PlayerSessionRegistry sessions) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.eventBus = eventBus;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到换频道");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到换频道");
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

        // 发 CC 请求（经消息总线发目标频道；M4 单进程 = 本进程，直接走迁移）
        ChangeChannelRequest req = new ChangeChannelRequest(chr.getId(), channelId, targetId,
                ChangeChannelRequest.Reason.PLAYER_CHANGE);
        LOG.info("玩家 {} 换频道 {} → {}（reason={}）", chr.getName(), channelId, targetId, req.reason());
        eventBus.send(MessageTargets.channel(targetId), req);

        // 迁移执行（M4 单进程内：定位表更新 + 地图清理 + 重新登记）
        intercoord.movePlayer(chr.getId(), targetId);
        if (chr.getMapObject() != null) {
            chr.getMapObject().removeCharacter(chr);
        }
        sessions.unregister(chr.getId());
        // 玩家重连目标频道端口 → PlayerLoggedinHandler 重新进图（v83 loading 界面）
        LOG.info("玩家 {} 换频道完成，等待重连频道 {}（{} 端口）", chr.getName(), targetId, targetId);
    }
}
