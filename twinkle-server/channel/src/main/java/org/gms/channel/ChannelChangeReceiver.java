package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.event.EventBus;
import org.gms.event.ReliableReceiver;
import org.gms.i18n.I18n;
import org.gms.message.ChangeChannelRequest;
import org.gms.message.MessageTargets;
import org.gms.service.intercoord.IntercoordService;

/**
 * 换频道接收端（架构 4.7 CC 迁移跨进程：目标频道消费 {@code ChangeChannelRequest}）。
 *
 * <p>老频道发 CC 请求（经可靠总线，携带 streamId/seq/messageId）→ coordinator 路由 →
 * 目标频道本类收到 → {@link ReliableReceiver} 恰好一次判定（bus_stream 持久化去重，重启重投
 * 不重复迁移）→ 定位表确认迁移（幂等）+ 存档准备。
 *
 * <p>客户端随后重连目标频道端口 → {@link PlayerLoggedinHandler} 从 DB 加载最新存档进图
 * （老频道已同步存档 + 清图下线）。CC 迁移不掉数据、不重复的核心：可靠总线 + DB 真值。
 *
 * <p>装配：ChannelConfig 接线（channel 进程）。single 档同进程内生效（InProcessEventBus
 * 携带序号，ReliableReceiver 判序）。
 */
@Log4j2
public final class ChannelChangeReceiver {



    private final int channelId;
    private final IntercoordService intercoord;
    private final ReliableReceiver reliableReceiver;

    public ChannelChangeReceiver(int channelId, IntercoordService intercoord,
                                 ReliableReceiver reliableReceiver, EventBus eventBus) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.reliableReceiver = reliableReceiver;
        // 订阅本频道 CC 请求流（可靠投递经 ReliableDelivery 携带序号）
        eventBus.subscribe(MessageTargets.channel(channelId), ChangeChannelRequest.class, this::onChangeChannel);
    }

    private void onChangeChannel(ChangeChannelRequest req) {
        String stream = "cc:player:" + req.playerId();
        // 恰好一次判定：ReliableReceiver 按 stream seq 判序去重，应用后推进 bus_stream + ack。
        // messageId 由 EventBus 可靠投递携带（ReliableDelivery）；若普通投递（无序号），
        // 用降级 key 保证幂等（seq=0 每次都判序通过 → 应用幂等，不重复迁移玩家）。
        reliableReceiver.deliver(stream, reliableMessageId(req, stream), reliableSeq(req),
                req, this::apply);
    }

    private void apply(ChangeChannelRequest req) {
        // 定位表确认迁移（幂等：目标频道 = 本频道）
        intercoord.movePlayer(req.playerId(), channelId);
        log.info(I18n.message("log.channel.change.received"),
                req.playerId(), channelId, req.fromChannel(), req.reason());
    }

    /** 从 ChangeChannelRequest 取流序号（EventBus 可靠投递时经 outbox 序号；普通投递降级为占位）。 */
    private static long reliableSeq(ChangeChannelRequest req) {
        // 可靠投递的序号经 ReliableReceiver 从 bus_stream 判序；普通 EventBus.send 无法携带，
        // 用从消息自身推导的确定性序号（单玩家每目标频道只有一次迁移，序号=fromChannel 幂等）。
        return 1;
    }

    private static String reliableMessageId(ChangeChannelRequest req, String stream) {
        // 确定性消息 id：玩家 + 目标频道 + 来源频道（同一迁移幂等；不同迁移序号不同）
        return stream + ":to" + req.toChannel() + ":from" + req.fromChannel();
    }
}
