package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.event.EventBus;
import org.gms.service.admin.OnlinePlayerEvents;
import org.gms.service.intercoord.IntercoordService;

/**
 * 玩家定位绑定（架构 4.4 定位表：进图注册、断链注销、换频道更新）。
 *
 * <p>订阅 {@code OnlinePlayerEvents}（进图/下线事件由 ChannelEventPublisher 发出）：
 * <ul>
 *   <li>{@code PlayerOnline} → 定位表 register（player → 本频道）。</li>
 *   <li>{@code PlayerOffline} → 定位表 remove。</li>
 * </ul>
 *
 * <p>换频道（CC）由 {@link ChangeChannelHandler} 直接调 {@code movePlayer}，不经本绑定。
 */
public final class ChannelLocationBinder {

    private static final Logger LOG = LogManager.getLogger(ChannelLocationBinder.class);

    private final int channelId;
    private final IntercoordService intercoord;

    public ChannelLocationBinder(int channelId, IntercoordService intercoord, EventBus eventBus) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        eventBus.subscribe(OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOnline.class, this::onOnline);
        eventBus.subscribe(OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOffline.class, this::onOffline);
    }

    private void onOnline(OnlinePlayerEvents.PlayerOnline event) {
        intercoord.registerPlayer(event.characterId(), channelId);
        intercoord.heartbeatChannel(channelId, intercoord.onlineOnChannel(channelId));
        LOG.debug("定位表登记: player={} channel={}", event.characterId(), channelId);
    }

    private void onOffline(OnlinePlayerEvents.PlayerOffline event) {
        intercoord.unregisterPlayer(event.characterId());
        intercoord.heartbeatChannel(channelId, intercoord.onlineOnChannel(channelId));
        LOG.debug("定位表注销: player={}", event.characterId());
    }
}
