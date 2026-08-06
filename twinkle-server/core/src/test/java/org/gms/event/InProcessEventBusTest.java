package org.gms.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventBus 进程内实现测试。
 *
 * <p>验证：基本派发、target 精确匹配、订阅者抛异常不影响其他订阅者、取消订阅。
 */
class InProcessEventBusTest {

    static final class Payload {
        final String value;
        Payload(String value) { this.value = value; }
    }

    @Test
    void sendDispatchesToSubscribers() throws Exception {
        InProcessEventBus bus = new InProcessEventBus();
        AtomicReference<Payload> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (var sub = bus.subscribe("topic", Payload.class, p -> {
            got.set(p);
            latch.countDown();
        })) {
            bus.send("topic", new Payload("hi")).get();
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(got.get().value).isEqualTo("hi");
        }
    }

    @Test
    void targetExactMatch_onlyMatchingTargetReceives() throws Exception {
        InProcessEventBus bus = new InProcessEventBus();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        try (var subA = bus.subscribe("channel-a", Payload.class, p -> aCount.incrementAndGet());
             var subB = bus.subscribe("channel-b", Payload.class, p -> bCount.incrementAndGet())) {
            bus.send("channel-a", new Payload("x")).get();
            assertThat(aCount.get()).isEqualTo(1);
            assertThat(bCount.get()).isZero();
        }
    }

    @Test
    void subscriberFailureDoesNotBreakOthers() throws Exception {
        InProcessEventBus bus = new InProcessEventBus();
        AtomicInteger okCount = new AtomicInteger();
        bus.subscribe("topic", Payload.class, p -> { throw new RuntimeException("boom"); });
        bus.subscribe("topic", Payload.class, p -> okCount.incrementAndGet());
        bus.send("topic", new Payload("x")).get();
        assertThat(okCount.get()).isEqualTo(1);
    }

    @Test
    void unsubscribe_removesHandler() throws Exception {
        InProcessEventBus bus = new InProcessEventBus();
        AtomicInteger count = new AtomicInteger();
        var sub = bus.subscribe("topic", Payload.class, p -> count.incrementAndGet());
        bus.send("topic", new Payload("a")).get();
        sub.close();
        bus.send("topic", new Payload("b")).get();
        assertThat(count.get()).isEqualTo(1);
    }
}
