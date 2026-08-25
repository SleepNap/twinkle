package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;
import org.gms.service.admin.OnlinePlayerEvents;
import org.gms.service.intercoord.IntercoordService;

/**
 * 大区 Presence 绑定（架构 4.3.1/4.4：进图注册、真断链注销、活动状态切换）。
 *
 * <p>订阅 {@code OnlinePlayerEvents}：
 * <ul>
 *   <li>{@code PlayerOnline} → Presence register（world + TCP 属主频道 + IN_CHANNEL）。</li>
 *   <li>{@code PlayerActivityChanged} → 保持大区在线与 TCP 属主，只切 activity。</li>
 *   <li>{@code PlayerOffline} → 真正断链后 Presence remove。</li>
 * </ul>
 *
 * <p>换频道开始由 {@link ChangeChannelHandler} 标记 CHANNEL_TRANSITION；目标频道完成登录后
 * PlayerOnline 覆盖为新属主。
 */
@Log4j2
public final class ChannelLocationBinder {



    private final int channelId;
    private final int worldId;
    private final IntercoordService intercoord;

    public ChannelLocationBinder(int worldId, int channelId, IntercoordService intercoord, EventBus eventBus) {
        this.worldId = worldId;
        this.channelId = channelId;
        this.intercoord = intercoord;
        eventBus.subscribe(OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOnline.class, this::onOnline);
        eventBus.subscribe(OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOffline.class, this::onOffline);
        eventBus.subscribe(OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerActivityChanged.class,
                this::onActivityChanged);
    }

    /** 单世界兼容构造。 */
    public ChannelLocationBinder(int channelId, IntercoordService intercoord, EventBus eventBus) {
        this(0, channelId, intercoord, eventBus);
    }

    private void onOnline(OnlinePlayerEvents.PlayerOnline event) {
        intercoord.registerPlayer(event.characterId(), worldId, channelId);
        intercoord.heartbeatChannel(channelId, intercoord.onlineOnChannel(channelId));
        log.debug(I18n.message("log.channel.location.register"), event.characterId(), channelId);
    }

    private void onOffline(OnlinePlayerEvents.PlayerOffline event) {
        intercoord.unregisterPlayer(event.characterId());
        intercoord.heartbeatChannel(channelId, intercoord.onlineOnChannel(channelId));
        log.debug(I18n.message("log.channel.location.unregister"), event.characterId());
    }

    private void onActivityChanged(OnlinePlayerEvents.PlayerActivityChanged event) {
        if (event.worldId() != worldId || event.ownerChannelId() != channelId) {
            return;
        }
        intercoord.updatePlayerActivity(event.characterId(), event.activity());
        intercoord.heartbeatChannel(channelId, intercoord.onlineOnChannel(channelId));
    }
}
