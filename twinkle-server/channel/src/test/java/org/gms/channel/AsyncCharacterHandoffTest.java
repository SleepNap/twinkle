package org.gms.channel;

import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.concurrent.GameExecution;
import org.gms.concurrent.ThreadManager;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.event.EventBus;
import org.gms.event.OutboxRepository;
import org.gms.event.PayloadCodec;
import org.gms.event.ReliableEventBus;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.SessionStage;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.persistence.repo.PlayerCharacterSnapshotRepository;
import org.gms.service.intercoord.ChannelDirectoryService;
import org.gms.service.intercoord.IntercoordService;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.resource.MapResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 异步边界用闩锁控制数据库等待，不需要真实客户端或数据库。 */
public class AsyncCharacterHandoffTest {
    @Test public void failedInitializerRemovesCandidateAndAllowsRetry(@TempDir Path root) throws Exception {
        failedLoginCanRetry(root, false);
    }

    @Test public void missingMapPreservesPreviousSessionAndAllowsRetry(@TempDir Path root) throws Exception {
        failedLoginCanRetry(root, true);
    }

    private static void failedLoginCanRetry(Path root, boolean missingMap) throws Exception {
        Path mapFile = Files.createDirectories(root.resolve("Map.wz/Map/Map1")).resolve("100000000.img.xml");
        Files.writeString(mapFile, "<imgdir name=\"100000000.img\"><imgdir name=\"info\">"
                + "<int name=\"returnMap\" value=\"100000000\"/></imgdir></imgdir>");
        var versions = new DefaultVersionGate(); var loader = new PlayerCharacterAssembler(versions);
        var template = new MapleMap(); template.setMapId(100000000);
        var previous = new GameplayTestSession(1, template);
        var saved = loader.toData(previous.character);
        if (missingMap) saved.setMap(999999999);
        var repository = proxy(PlayerCharacterRepository.class, (p, method, args) ->
                method.getName().equals("findById") ? Optional.of(saved) : null);
        try (var execution = new GameExecution("failed-login", versions); var background = new ThreadManager();
             var queue = new CharacterSaveQueue((PlayerCharacterSnapshotRepository) (c, i, q, p, s) -> { },
                     loader, new PlayerStorage())) {
            var sessions = new PlayerSessionRegistry(execution); var players = new PlayerStorage(execution);
            var resources = new WzResourceRegistry(root, List.of(new MapResourceLoader()), Runnable::run);
            var maps = new ChannelMapManager(resources, 1, execution);
            var spawns = new MonsterSpawnService(GameDataProvider.fixed(Map.of(), Map.of()), sessions, null);
            try {
                execution.run(() -> { players.add(previous.character); sessions.claim(1, previous); });
                var handler = new PlayerLoggedinHandler(repository, loader, maps, players, sessions, spawns,
                        1, null, null, background, queue);
                var failed = new GameplayTestSession(1, template);
                failed.transition(SessionStage.LOGIN); failed.setAttr("character", null);
                failed.setAttr("afterLogin", (Runnable) () -> { throw new IllegalStateException("模拟初始化失败"); });
                var input = new ByteArrayOutPacket(); input.writeInt(1);
                execution.run(() -> handler.handle(failed, new ByteArrayInPacket(input.getBytes())));
                await().atMost(Duration.ofSeconds(5)).until(() -> failed.stage() == SessionStage.HANDSHAKE);
                assertThat(execution.call(() -> failed.getAttr("character") == null)).isTrue();
                assertThat(execution.call(() -> failed.getAttr("afterLogin") == null)).isTrue();
                assertThat(execution.call(players::count)).isEqualTo(missingMap ? 1 : 0);
                if (missingMap) {
                    assertThat(sessions.get(1)).isSameAs(previous);
                    assertThat(previous.stage()).isEqualTo(SessionStage.IN_GAME);
                    assertThat((Object) previous.getAttr("stateTransfer")).isNull();
                    assertThat(template.characters()).contains(previous.character);
                } else {
                    assertThat(sessions.get(1)).isNull();
                    assertThat(execution.call(() -> maps.getMap(100000000).characters())).isEmpty();
                }
                saved.setMap(100000000);
                var retry = new GameplayTestSession(1, template);
                retry.transition(SessionStage.LOGIN); retry.setAttr("character", null);
                execution.run(() -> handler.handle(retry, new ByteArrayInPacket(input.getBytes())));
                await().atMost(Duration.ofSeconds(5)).until(() -> execution.call(() -> sessions.get(1) == retry));
                assertThat(execution.call(players::count)).isEqualTo(1);
            } finally { spawns.close(); }
        }
    }

    @Test public void transferFreezesOnlyTheMovingPlayerAndPublishesAfterCommit() throws Exception {
        var versions = new DefaultVersionGate();
        CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean stored = new AtomicBoolean(), published = new AtomicBoolean();
        AtomicInteger mutations = new AtomicInteger();
        var directory = proxy(IntercoordService.class, (p, method, args) -> {
            if (method.getName().equals("channel"))
                return Optional.of(new ChannelDirectoryService.ChannelInfo(2, "127.0.0.1", 8586, 0));
            if (method.getName().equals("beginChannelTransfer")) {
                assertThat(stored.get()).isTrue(); published.set(true);
            }
            return null;
        });
        var outbox = proxy(OutboxRepository.class, (p, method, args) -> switch (method.getName()) {
            case "findPending" -> List.of();
            case "lastIssuedSeq" -> 0L;
            case "insert" -> { assertThat(stored.get()).isTrue(); yield 1L; }
            default -> null;
        });
        var bus = proxy(EventBus.class, (p, method, args) -> CompletableFuture.completedFuture(null));
        AtomicReference<Object> payload = new AtomicReference<>();
        var codec = new PayloadCodec() {
            @Override public String encode(Object value) { payload.set(value); return "test-payload"; }
            @Override public Object decode(String value, String type) { return payload.get(); }
        };
        try (var execution = new GameExecution("cc-test", versions); var background = new ThreadManager();
             var queue = new CharacterSaveQueue((PlayerCharacterSnapshotRepository) (c, i, q, p, s) -> {
                 saving.countDown(); waitFor(release); stored.set(true);
             }, new PlayerCharacterAssembler(versions), new PlayerStorage())) {
            var sessions = new PlayerSessionRegistry(execution); var players = new PlayerStorage(execution);
            var session = new GameplayTestSession(1, new MapleMap());
            var registry = new HandlerRegistry(execution);
            var change = new ChangeChannelHandler(1, directory, new ReliableEventBus(bus, outbox, codec),
                    sessions, players, queue, background);
            registry.register(RecvOpcode.CHANGE_CHANNEL, change);
            registry.register(RecvOpcode.MOVE_PLAYER, (s, p) -> mutations.incrementAndGet());
            execution.run(() -> { players.add(session.character); sessions.claim(1, session); });
            ByteArrayOutPacket packet = new ByteArrayOutPacket(); packet.writeInt(0); packet.writeByte(1);
            registry.dispatch(RecvOpcode.CHANGE_CHANNEL.getValue(), session, new ByteArrayInPacket(packet.getBytes()));
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                registry.dispatch(RecvOpcode.MOVE_PLAYER.getValue(), session, new ByteArrayInPacket(new byte[0]));
                assertThat(execution.call(() -> 123)).isEqualTo(123);
                assertThat(mutations.get()).isZero();
                assertThat(sessions.get(1)).isSameAs(session);
                assertThat(published.get()).isFalse();
            } finally { release.countDown(); }
            await().atMost(Duration.ofSeconds(5)).until(() -> session.stage() == SessionStage.CHANNEL_TRANSITION);
            assertThat(execution.call(() -> players.getById(1))).isNull();
            assertThat(sessions.get(1)).isNull();
            assertThat(published.get()).isTrue();
        } finally { release.countDown(); }
    }

    @Test public void slowLoginCannotBlockTheChannelOrAdmitASecondLoad(@TempDir Path root) throws Exception {
        loginWhileDatabaseWaits(root, false);
    }

    @Test public void disconnectDuringLoginCannotRecreateAnOnlineCharacter(@TempDir Path root) throws Exception {
        loginWhileDatabaseWaits(root, true);
    }

    private static void loginWhileDatabaseWaits(Path root, boolean disconnect) throws Exception {
        Path mapFile = Files.createDirectories(root.resolve("Map.wz/Map/Map1")).resolve("100000000.img.xml");
        Files.writeString(mapFile, "<imgdir name=\"100000000.img\"><imgdir name=\"info\">"
                + "<int name=\"returnMap\" value=\"100000000\"/></imgdir></imgdir>");
        var versions = new DefaultVersionGate();
        var loader = new PlayerCharacterAssembler(versions);
        var templateMap = new MapleMap(); templateMap.setMapId(100000000);
        var session = new GameplayTestSession(1, templateMap);
        var saved = loader.toData(session.character);
        session.transition(SessionStage.LOGIN); session.setAttr("character", null);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), readFinished = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger(); AtomicBoolean initialized = new AtomicBoolean();
        var repository = proxy(PlayerCharacterRepository.class, (p, method, args) -> {
            if (!method.getName().equals("findById")) return null;
            loads.incrementAndGet(); reading.countDown(); waitFor(release); readFinished.countDown();
            return Optional.of(saved);
        });
        try (var execution = new GameExecution("login-test", versions); var background = new ThreadManager();
             var queue = new CharacterSaveQueue((PlayerCharacterSnapshotRepository) (c, i, q, p, s) -> { },
                     loader, new PlayerStorage())) {
            var sessions = new PlayerSessionRegistry(execution); var players = new PlayerStorage(execution);
            var resources = new WzResourceRegistry(root, List.of(new MapResourceLoader()), Runnable::run);
            var maps = new ChannelMapManager(resources, 1, execution);
            var spawns = new MonsterSpawnService(GameDataProvider.fixed(Map.of(), Map.of()), sessions, null);
            try {
                var handler = new PlayerLoggedinHandler(repository, loader, maps, players, sessions, spawns,
                        1, null, null, background, queue);
                session.setAttr("afterLogin", (Runnable) () -> { execution.requireOwner(); initialized.set(true); });
                ByteArrayOutPacket packet = new ByteArrayOutPacket(); packet.writeInt(1);
                execution.run(() -> handler.handle(session, new ByteArrayInPacket(packet.getBytes())));
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    var duplicate = new GameplayTestSession(1, templateMap); duplicate.transition(SessionStage.LOGIN);
                    execution.run(() -> handler.handle(duplicate, new ByteArrayInPacket(packet.getBytes())));
                    assertThat(duplicate.stage()).isEqualTo(SessionStage.HANDSHAKE);
                    assertThat(loads.get()).isEqualTo(1);
                    assertThat(execution.call(players::count)).isZero();
                    if (disconnect) session.setAttr("transportClosed", true);
                } finally { release.countDown(); }
                assertThat(readFinished.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(5)).until(() -> execution.call(() -> session.getAttr("stateTransfer") == null));
                assertThat(execution.call(players::count)).isEqualTo(disconnect ? 0 : 1);
                assertThat(initialized.get()).isEqualTo(!disconnect);
            } finally { spawns.close(); }
        } finally { release.countDown(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
