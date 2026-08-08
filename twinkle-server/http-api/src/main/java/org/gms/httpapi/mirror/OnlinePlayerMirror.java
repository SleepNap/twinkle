package org.gms.httpapi.mirror;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.event.EventBus;
import org.gms.service.admin.OnlinePlayerEvents;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家只读镜像（架构 M3-1 数据三路第③路：事件驱动快照）。
 *
 * <p>频道进程推 {@link OnlinePlayerEvents}（进图/下线）→ 本镜像更新 → HTTP 读镜像。
 * <b>单向只读纪律</b>：本类只订阅事件、维护内存快照，禁止经镜像回写频道（写操作一律走
 * {@code AdminService} 第②路，共享状态单一属主铁律）。
 *
 * <p>实现细节：{@code Map<characterId, PlayerOnline>} 只读快照；订阅由 bootstrap 接线
 * （{@code EventBus.subscribe}）。并发用 ConcurrentHashMap（进图/断链可并行到达）。
 */
public final class OnlinePlayerMirror implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(OnlinePlayerMirror.class);

    private final Map<Long, OnlinePlayerEvents.PlayerOnline> byId = new ConcurrentHashMap<>();
    private final AutoCloseable subscriptionOnline;
    private final AutoCloseable subscriptionOffline;

    public OnlinePlayerMirror(EventBus bus) {
        this.subscriptionOnline = bus.subscribe(
                OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOnline.class, this::onOnline);
        this.subscriptionOffline = bus.subscribe(
                OnlinePlayerEvents.TARGET, OnlinePlayerEvents.PlayerOffline.class, this::onOffline);
    }

    private void onOnline(OnlinePlayerEvents.PlayerOnline ev) {
        byId.put(ev.characterId(), ev);
    }

    private void onOffline(OnlinePlayerEvents.PlayerOffline ev) {
        byId.remove(ev.characterId());
    }

    /** 在线人数。 */
    public int onlineCount() {
        return byId.size();
    }

    /** 在线玩家只读快照（不可变列表）。 */
    public List<OnlinePlayerEvents.PlayerOnline> snapshot() {
        return List.copyOf(byId.values());
    }

    @Override
    public void close() {
        try {
            subscriptionOnline.close();
            subscriptionOffline.close();
        } catch (Exception e) {
            LOG.warn("关闭在线镜像订阅异常", e);
        }
    }
}
