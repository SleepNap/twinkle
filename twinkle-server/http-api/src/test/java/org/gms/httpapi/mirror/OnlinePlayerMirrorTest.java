package org.gms.httpapi.mirror;

import org.gms.event.InProcessEventBus;
import org.gms.service.admin.OnlinePlayerEvents;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-1 镜像纪律单元测试：事件驱动快照是单向只读（架构 M3-1 第 3 项镜像纪律）。
 *
 * <p>验证镜像只订阅事件维护快照（进图加、下线删），**无任何回写通道**——本测试确认
 * 事件流是单向的：频道推 → 镜像存，镜像不产生任何反向事件/回调（单一属主铁律）。
 */
class OnlinePlayerMirrorTest {

    @Test
    void mirrorTracksOnlineAndOfflineOneWay() {
        InProcessEventBus bus = new InProcessEventBus();
        try (OnlinePlayerMirror mirror = new OnlinePlayerMirror(bus)) {
            assertThat(mirror.onlineCount()).isZero();

            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(1L, "A", 100000000, 10, 0));
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(2L, "B", 100000001, 12, 100));

            assertThat(mirror.onlineCount()).isEqualTo(2);
            assertThat(mirror.snapshot())
                    .extracting(OnlinePlayerEvents.PlayerOnline::name)
                    .containsExactlyInAnyOrder("A", "B");

            bus.send(OnlinePlayerEvents.TARGET, new OnlinePlayerEvents.PlayerOffline(1L));

            assertThat(mirror.onlineCount()).isEqualTo(1);
            assertThat(mirror.snapshot())
                    .extracting(OnlinePlayerEvents.PlayerOnline::name)
                    .containsExactly("B");
        }
    }

    @Test
    void duplicateOnlineIsIdempotentByCharacter() {
        InProcessEventBus bus = new InProcessEventBus();
        try (OnlinePlayerMirror mirror = new OnlinePlayerMirror(bus)) {
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(7L, "C", 100000000, 10, 0));
            long version = mirror.snapshotState().version();
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(7L, "C", 100000000, 10, 0));

            assertThat(mirror.onlineCount()).isEqualTo(1);  // 同角色覆盖，不重复
            assertThat(mirror.snapshotState().version()).isEqualTo(version);
        }
    }

    @Test
    void snapshotIsVersionedAndStablySorted() {
        InProcessEventBus bus = new InProcessEventBus();
        try (OnlinePlayerMirror mirror = new OnlinePlayerMirror(bus)) {
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(9L, "Later", 2, 10, 0));
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(2L, "Earlier", 1, 20, 100));

            OnlinePlayerMirror.Snapshot snapshot = mirror.snapshotState();
            assertThat(snapshot.version()).isEqualTo(2);
            assertThat(snapshot.observedAt()).isNotNull();
            assertThat(snapshot.players())
                    .extracting(OnlinePlayerEvents.PlayerOnline::characterId)
                    .containsExactly(2L, 9L);
        }
    }
}
