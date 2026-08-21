package org.gms.tick;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 游戏循环：手动驱动 tick、handler 异常隔离、真实线程启动/暂停/恢复（热重载安全点）。
 */
class GameTickLoopTest {

    @Test
    @DisplayName("手动 tickOnce 驱动 handler，tickCount 递增")
    void tickOnceDrivesHandlers() {
        GameTickLoop loop = new GameTickLoop(1);
        AtomicLong seen = new AtomicLong();
        loop.register(seen::set);

        loop.tickOnce();
        loop.tickOnce();

        assertThat(loop.tickCount()).isEqualTo(2);
        assertThat(seen.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("handler 异常不中断其他 handler")
    void handlerExceptionIsolated() {
        GameTickLoop loop = new GameTickLoop(1);
        List<Long> healthyCalls = new ArrayList<>();
        loop.register(tick -> {
            throw new IllegalStateException("boom");
        });
        loop.register(tick -> healthyCalls.add(tick));

        loop.tickOnce();

        assertThat(healthyCalls).containsExactly(1L);
        assertThat(loop.lastTickError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("register/unregister 影响 handler 数")
    void registerUnregister() {
        GameTickLoop loop = new GameTickLoop(1);
        TickHandler h = tick -> {
        };

        loop.register(h);
        assertThat(loop.handlerCount()).isEqualTo(1);
        loop.unregister(h);
        assertThat(loop.handlerCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("真实线程循环：start 后 tick 自动推进，stop 停止")
    void realThreadTicksThenStops() {
        GameTickLoop loop = new GameTickLoop(5);

        loop.start();
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(loop.tickCount()).isGreaterThan(0));

        long afterStart = loop.tickCount();
        loop.stop();
        assertThat(loop.isPaused()).isFalse();
        long afterStop = loop.tickCount();
        // stop 后线程已停：等一小段，tick 数不再增长
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(loop.tickCount()).isEqualTo(afterStop);
        assertThat(afterStop).isGreaterThanOrEqualTo(afterStart);
    }

    @Test
    @DisplayName("pause 停在安全点（tick 不推进），resume 恢复")
    void pauseStopsTickingResumeRestores() {
        GameTickLoop loop = new GameTickLoop(5);
        loop.start();
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(loop.tickCount()).isGreaterThan(0));

        loop.pause();
        long pausedAt = loop.tickCount();
        try {
            Thread.sleep(40); // 若未真正暂停，40ms/5ms = 8 tick
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(loop.tickCount()).isEqualTo(pausedAt); // 安全点：tick 未推进

        loop.resume();
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(loop.tickCount()).isGreaterThan(pausedAt));
        loop.stop();
    }

    @Test
    @DisplayName("tick 序号从 1 起单调递增")
    void tickCountStartsAtOneAndMonotonic() {
        GameTickLoop loop = new GameTickLoop(1);
        AtomicInteger max = new AtomicInteger();
        loop.register(tick -> {
            if (tick <= max.get()) {
                throw new IllegalStateException("tick 序号回退: " + tick + " <= " + max.get());
            }
            max.set((int) tick);
        });

        loop.tickOnce();
        loop.tickOnce();
        loop.tickOnce();
        assertThat(loop.tickCount()).isEqualTo(3);
        assertThat(max.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("业务周期按基础周期向上换算，不提前执行")
    void ticksForRoundsUpUsingConfiguredInterval() {
        GameTickLoop loop = new GameTickLoop(250);

        assertThat(loop.intervalMillis()).isEqualTo(250);
        assertThat(loop.ticksFor(1_000)).isEqualTo(4);
        assertThat(loop.ticksFor(1_001)).isEqualTo(5);
    }
}
