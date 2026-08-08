package org.gms.channel.admin;

import org.gms.domain.game.Character;
import org.gms.event.EventBus;
import org.gms.net.packet.PacketSession;
import org.gms.service.admin.OnlinePlayerEvents;

/**
 * 频道在线事件发布（架构 M3-1 数据三路第③路：频道进程推变更 → 管理进程只读镜像）。
 *
 * <p>进图/断链时调用，经 {@link EventBus} 广播 {@link OnlinePlayerEvents}。实现为
 * 轻量门面，广播失败（无订阅者/异常）不影响游戏流程。
 */
public final class ChannelEventPublisher {

    private final EventBus bus;

    public ChannelEventPublisher(EventBus bus) {
        this.bus = bus;
    }

    /** 玩家进图（PlayerLoggedinHandler 完成注册后调用）。 */
    public void playerOnline(Character chr) {
        bus.send(OnlinePlayerEvents.TARGET,
                new OnlinePlayerEvents.PlayerOnline(chr.getId(), chr.getName(), chr.getMap(), chr.getLevel(), chr.getJob()));
    }

    /** 玩家断链/下线（DisconnectListener 注销前调用）。 */
    public void playerOffline(long characterId) {
        bus.send(OnlinePlayerEvents.TARGET, new OnlinePlayerEvents.PlayerOffline(characterId));
    }

    /** 从会话取角色 id 并发下线事件（断链回调用）。 */
    public void playerOffline(PacketSession session) {
        Character chr = session.getAttr("character");
        if (chr != null) {
            playerOffline(chr.getId());
        }
    }
}
