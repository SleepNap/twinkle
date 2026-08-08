package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.event.EventBus;
import org.gms.message.MessageTargets;
import org.gms.message.NoticeMessage;
import org.gms.message.WhisperRequest;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.service.intercoord.IntercoordService;

/**
 * 频道消息订阅（架构 4.4 消息总线：订阅本频道 target，接收跨频道投递的消息并派发）。
 *
 * <p>单进程内经 EventBus 精确 target（{@code channel:{id}}）订阅：
 * <ul>
 *   <li>{@link WhisperRequest}：跨频道悄悄话 → 本频道目标会话回 WHISPER 包。</li>
 *   <li>{@link NoticeMessage}：喇叭/公告广播 → 本频道全体 SERVERMESSAGE。</li>
 * </ul>
 *
 * <p>M6 分布式时本订阅改由网络 EventBus 实现驱动（接口不变），派发逻辑同构。
 */
public final class ChannelMessageSubscriber {

    private static final Logger LOG = LogManager.getLogger(ChannelMessageSubscriber.class);

    private final int channelId;
    private final IntercoordService intercoord;
    private final PlayerSessionRegistry sessions;

    public ChannelMessageSubscriber(int channelId, IntercoordService intercoord,
                                    PlayerSessionRegistry sessions, EventBus eventBus) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.sessions = sessions;
        // 订阅本频道精确 target：跨频道悄悄话/公告投递
        eventBus.subscribe(MessageTargets.channel(channelId), WhisperRequest.class, this::deliverWhisper);
        eventBus.subscribe(MessageTargets.channel(channelId), NoticeMessage.class, this::deliverNotice);
    }

    private void deliverWhisper(WhisperRequest req) {
        // 定位校验：目标必须在本频道（防跨频道消息投到错误频道）
        if (intercoord.locate(req.toId()).orElse(-1) != channelId) {
            LOG.warn("悄悄话投递目标不在本频道: toId={} channel={}", req.toId(), channelId);
            return;
        }
        PacketSession target = sessions.get(req.toId());
        if (target != null) {
            target.send(WhisperHandler.whisperPacket(req));
        }
    }

    private void deliverNotice(NoticeMessage notice) {
        // 广播公告到本频道全体在线玩家（架构 4.4 消息总线：广播 = coordinator 群发所有频道）
        OutPacket packet = org.gms.channel.ChannelPacketFactory.serverNotice(notice.content());
        for (PacketSession s : sessions.all()) {
            s.send(packet);
        }
        LOG.info("频道 {} 收到公告: {}", channelId, notice.content());
    }
}
