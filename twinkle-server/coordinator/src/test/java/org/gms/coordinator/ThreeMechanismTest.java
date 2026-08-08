package org.gms.coordinator;

import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 频道间三机制基础设施测试（架构 4.4：单一属主 / 定位表 / 频道注册）。
 */
class ThreeMechanismTest {

    private IntercoordService svc;

    @BeforeEach
    void setUp() {
        svc = new CoordinatorService(new LocationTable(), new ChannelRegistry(), new SingleOwnerStore());
    }

    // ---- 定位表 ----

    @Test
    void locationTableRegisterLocateRemove() {
        svc.registerPlayer(1001, 1);
        assertThat(svc.locate(1001)).contains(1);
        assertThat(svc.locate(9999)).isEmpty();

        svc.movePlayer(1001, 2); // 换频道
        assertThat(svc.locate(1001)).contains(2);

        svc.unregisterPlayer(1001);
        assertThat(svc.locate(1001)).isEmpty();
        assertThat(svc.onlineOnChannel(2)).isZero();
    }

    // ---- 频道注册 ----

    @Test
    void channelRegistryRegisterHeartbeat() {
        svc.registerChannel(1, "127.0.0.1", 8584, 5);
        assertThat(svc.channel(1)).isPresent();
        assertThat(svc.channel(1).get().onlineCount()).isEqualTo(5);

        svc.heartbeatChannel(1, 7);
        assertThat(svc.channel(1).get().onlineCount()).isEqualTo(7);
    }

    // ---- 单一属主存储 ----

    @Test
    void singleOwnerWriteReadWithVersion() {
        long v1 = svc.write("notice", "欢迎", -1);
        assertThat(v1).isEqualTo(1);
        assertThat(svc.read("notice").get().value()).isEqualTo("欢迎");

        // 版本冲突拒绝（防覆盖：另一写者改了，本写者用旧版本）
        assertThatThrownBy(() -> svc.write("notice", "覆盖", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    void singleOwnerCounterIncrement() {
        long v1 = svc.increment("shop:fund", 100);
        assertThat(v1).isEqualTo(100);
        long v2 = svc.increment("shop:fund", -30);
        assertThat(v2).isEqualTo(70);
        assertThat(svc.storeSnapshot().containsKey("shop:fund")).isTrue();
    }
}
