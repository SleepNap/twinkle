package org.gms.tick;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class TickSafePointTest {
    @Test public void pauseAcknowledgesCompletionOfTheActiveTick() throws Exception {
        var loop = new GameTickLoop(10);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        loop.register(tick -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        });
        var active = CompletableFuture.runAsync(loop::tickOnce);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var paused = CompletableFuture.runAsync(loop::pause);
        try {
            await().atMost(Duration.ofSeconds(5)).until(loop::isPaused);
            assertThat(paused.isDone()).isFalse();
        } finally { release.countDown(); }
        active.get(5, TimeUnit.SECONDS); paused.get(5, TimeUnit.SECONDS);
        loop.tickOnce();
        assertThat(loop.tickCount()).isEqualTo(1);
    }
}
