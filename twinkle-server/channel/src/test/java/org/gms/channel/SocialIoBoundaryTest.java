package org.gms.channel;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.gms.concurrent.GameExecution;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.concurrent.ThreadManager;
import org.gms.domain.game.map.MapleMap;
import org.gms.event.InProcessEventBus;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.service.admin.OnlinePlayerEvents;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;


public class SocialIoBoundaryTest {
    @Test public void slowPresenceDoesNotBlockChannelAndLifecycleKeepsOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        IntercoordService intercoord = (IntercoordService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IntercoordService.class}, (proxy, method, args) -> {
            if (method.getName().equals("registerPlayer")) { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
            calls.add(method.getName());
            return method.getReturnType() == int.class ? 1 : null;
        });
        try (var background = new ThreadManager(); var io = new SerialTaskQueue(background, 8);
             var execution = new GameExecution("presence-test", new DefaultVersionGate())) {
            var bus = new InProcessEventBus();
            try (var binder = new ChannelLocationBinder(0, 1, intercoord, bus).async(io)) {
                execution.run(() -> bus.send(OnlinePlayerEvents.TARGET, new OnlinePlayerEvents.PlayerOnline(1, "Hero", 0, 1, 0, 1)));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(execution.submit(() -> 7).get(1, TimeUnit.SECONDS)).isEqualTo(7);
                    execution.run(() -> bus.send(OnlinePlayerEvents.TARGET, new OnlinePlayerEvents.PlayerOffline(1, 1)));
                } finally { release.countDown(); }
                io.awaitIdle(Duration.ofSeconds(2));
                assertThat(calls.indexOf("unregisterOwnedPlayer")).isGreaterThan(calls.indexOf("registerPlayer"));
            }
        }
    }
    @Test public void buddyPersistenceDoesNotBlockAndOldSessionGetsNoCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BuddyListRepository repository = (BuddyListRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BuddyListRepository.class}, (proxy, method, args) -> {
            if (method.getName().equals("insertIfAbsent")) { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return true; }
            return null;
        });
        IntercoordService intercoord = (IntercoordService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IntercoordService.class}, (proxy, method, args) -> { throw new AssertionError("Unexpected synchronous presence lookup"); });
        var gate = new DefaultVersionGate();
        try (var background = new ThreadManager(); var io = new SerialTaskQueue(background, 8);
             var execution = new GameExecution("buddy-test", gate)) {
            var sessions = new PlayerSessionRegistry(execution);
            var first = new GameplayTestSession(1, new MapleMap());
            var second = new GameplayTestSession(2, new MapleMap());
            var handler = new BuddyHandler(1, intercoord, new InProcessEventBus(), sessions, repository).async(io);
            execution.run(() -> { sessions.claim(1, first); sessions.claim(2, second); });
            var packet = new ByteArrayOutPacket(); packet.writeInt(0); packet.writeByte(0);
            byte[] name = "Player2".getBytes(StandardCharsets.UTF_8); packet.writeByte(name.length); packet.writeBytes(name);
            execution.run(() -> handler.handle(first, new ByteArrayInPacket(packet.getBytes())));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(execution.submit(() -> 9).get(1, TimeUnit.SECONDS)).isEqualTo(9);
                execution.run(() -> { sessions.unregister(1, first); gate.onReload(); });
            } finally { release.countDown(); }
            io.awaitIdle(Duration.ofSeconds(2)); execution.run(() -> { });
            assertThat(first.sent).isEmpty(); assertThat(second.sent).isEmpty();
        }
    }
}
