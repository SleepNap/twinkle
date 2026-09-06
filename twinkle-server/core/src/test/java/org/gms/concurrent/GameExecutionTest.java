package org.gms.concurrent;

import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GameExecutionTest {
    @Test public void serializesInputsAndAllowsOwnerReentry() throws Exception {
        try (GameExecution execution = new GameExecution("state-test", new DefaultVersionGate())) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            List<Integer> order = new ArrayList<>();
            var first = execution.submit(() -> { entered.countDown(); await(release); order.add(1); return null; });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = execution.submit(() -> { execution.run(() -> order.add(2)); return null; });
            assertThat(second.isDone()).isFalse();
            release.countDown();
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            assertThat(order).containsExactly(1, 2);
        }
    }

    @Test public void versionChangeCannotSplitAnAdmittedOperation() throws Exception {
        DefaultVersionGate versions = new DefaultVersionGate();
        try (GameExecution execution = new GameExecution("version-test", versions)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var ongoing = execution.submit(() -> {
                long epoch = execution.version();
                entered.countDown(); await(release);
                return versions.decide(epoch);
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            versions.onReload(); release.countDown();
            assertThat(ongoing.get(5, TimeUnit.SECONDS)).isEqualTo(VersionDecision.ALLOW);
            assertThat(execution.call(execution::version)).isEqualTo(2);
            assertThat(execution.continueAt(1, () -> { throw new AssertionError("旧回调不能写入"); }).join()).isFalse();
            assertThat(versions.decide(1)).isEqualTo(VersionDecision.STALE);
        }
    }

    @Test public void forbidsCrossChannelSynchronousWaits() {
        try (GameExecution first = new GameExecution("first", new DefaultVersionGate());
             GameExecution second = new GameExecution("second", new DefaultVersionGate())) {
            assertThatThrownBy(() -> first.run(() -> second.run(() -> { })))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(first::requireOwner).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test public void packetOverloadCannotDiscardDisconnectCleanup() throws Exception {
        try (GameExecution execution = new GameExecution("overload-test", new DefaultVersionGate())) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var blocker = execution.submit(() -> { entered.countDown(); await(release); return null; });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            List<CompletableFuture<Integer>> accepted = new ArrayList<>();
            for (int i = 0; i < 4095; i++) accepted.add(execution.submit(() -> 1));
            assertThat(execution.submit(() -> 1).isCompletedExceptionally()).isTrue();
            CompletableFuture<Void> cleanup = new CompletableFuture<>();
            execution.execute(() -> cleanup.complete(null));
            release.countDown(); blocker.get(5, TimeUnit.SECONDS); cleanup.get(5, TimeUnit.SECONDS);
            assertThat(accepted).allMatch(CompletableFuture::isDone);
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
