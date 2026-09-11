package org.gms.concurrent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;


public class SerialTaskQueueTest {
    @Test public void failureDoesNotBreakFifoAndOverloadIsExplicit() {
        AtomicReference<Runnable> drain = new AtomicReference<>();
        var queue = new SerialTaskQueue(drain::set, 2);
        List<Integer> order = new ArrayList<>();
        var first = queue.run(() -> { order.add(1); throw new IllegalStateException("failed"); });
        var second = queue.run(() -> order.add(2));
        assertThat(queue.run(() -> order.add(3))).isCompletedExceptionally();
        drain.get().run();
        queue.awaitIdle(Duration.ZERO);
        assertThat(first).isCompletedExceptionally(); assertThat(second).isCompleted();
        assertThat(order).containsExactly(1, 2);
        queue.close();
    }
}
