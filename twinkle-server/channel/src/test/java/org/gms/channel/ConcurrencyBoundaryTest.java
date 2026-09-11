package org.gms.channel;

import org.gms.logic.game.DefaultTradeSystem;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.logic.game.DefaultCombatSystem;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.concurrent.GameExecution;
import org.gms.concurrent.ThreadManager;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.SpawnPoint;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.SessionStage;
import org.gms.persistence.repo.PlayerCharacterSnapshotRepository;
import org.gms.domain.game.logic.CombatSystem;
import org.gms.domain.game.logic.TradeSystem;
import org.gms.service.intercoord.ChannelDirectoryService;
import org.gms.service.intercoord.IntercoordService;
import org.gms.tick.GameTickLoop;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 检查完整操作之间的交错，不连接客户端或外部数据库。 */
public class ConcurrencyBoundaryTest {
    @Test public void processRestartStopsAdmissionBeforeSavingTheFinalState() {
        var versions = new DefaultVersionGate();
        AtomicBoolean stopped = new AtomicBoolean(), stored = new AtomicBoolean(), restarted = new AtomicBoolean();
        try (var execution = new GameExecution("restart-owner", versions)) {
            var players = new PlayerStorage(execution);
            var character = new PlayerCharacter(1); character.setId(1);
            execution.run(() -> players.add(character));
            try (var queue = new CharacterSaveQueue((PlayerCharacterSnapshotRepository) (c, i, q, p, s) -> {
                assertThat(stopped.get()).isTrue();
                assertThat((int) c.getHp()).isEqualTo(20);
                stored.set(true);
            }, new PlayerCharacterAssembler(versions), players)) {
                var coordinator = new RestartCoordinator();
                var restart = new RestartService(coordinator, new GameTickLoop(10),
                        new EntityReloadService(new EntityReloadCoordinator(), versions), queue);
                restart.restart(() -> execution.run(() -> {
                    character.setHp(20); stopped.set(true);
                }), () -> { assertThat(stored.get()).isTrue(); restarted.set(true); });
                assertThat(restarted.get()).isTrue();
            }
        }
    }

    @Test public void concurrentEntrantsCannotCreateDuplicateMonsters() throws Exception {
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        MobData mob = new MobData(100100); mob.setMaxHp(100);
        GameDataProvider data = new GameDataProvider() {
            @Override public ItemData item(int id) { return null; }
            @Override public long version() { return 1; }
            @Override public MobData mob(int id) { reads.incrementAndGet(); loading.countDown(); waitFor(release); return mob; }
        };
        MapleMap map = new MapleMap(); map.addSpawnPoint(new SpawnPoint(100100, 0, 0));
        MonsterSpawnService spawns = new MonsterSpawnService(data, new PlayerSessionRegistry(), null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> spawns.ensureSpawned(map));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> spawns.ensureSpawned(map));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(map.monsters()).hasSize(1);
            assertThat(reads.get()).isEqualTo(1);
        } finally { release.countDown(); spawns.close(); }
    }

    @Test public void concurrentAttacksHaveExactlyOneDeathOwner() throws Exception {
        MobData data = new MobData(100100); data.setMaxHp(1);
        MapleMonster monster = new MapleMonster(data);
        CombatSystem combat = new DefaultCombatSystem(new DefaultVersionGate());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { waitFor(start); return combat.physicalAttack(new PlayerCharacter(1), monster, 100); });
            var second = pool.submit(() -> { waitFor(start); return combat.physicalAttack(new PlayerCharacter(1), monster, 100); });
            start.countDown();
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(CombatSystem.DamageResult::killed).count()).isEqualTo(1);
            assertThat(results.stream().mapToInt(CombatSystem.DamageResult::damage).sum()).isEqualTo(1);
            assertThat(monster.getHp()).isZero();
        }
    }

    @Test public void synchronousFlushUsesTheSameWriterAsAsyncSave() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        List<Long> written = new CopyOnWriteArrayList<>();
        PlayerCharacterSnapshotRepository repo = (character, items, quests, progress, skills) -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            if (character.getId() == 1) { writing.countDown(); waitFor(release); }
            written.add(character.getId()); active.decrementAndGet();
        };
        try (var queue = queue(repo); var pool = Executors.newSingleThreadExecutor()) {
            queue.save(new GameplayTestSession(1, new MapleMap()).character);
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            var sync = pool.submit(() -> queue.flushCharacterSync(new GameplayTestSession(2, new MapleMap()).character));
            await().atMost(Duration.ofSeconds(5)).until(() -> queue.pendingCount() == 2);
            assertThat(active.get()).isEqualTo(1);
            release.countDown(); sync.get(5, TimeUnit.SECONDS); queue.drain();
            assertThat(written).containsExactly(1L, 2L);
            assertThat(maximum.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test public void databaseWaitDoesNotLockPlayerOrLoseNewOfflineSnapshot() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> saved = new CopyOnWriteArrayList<>();
        PlayerCharacterSnapshotRepository repo = (character, items, quests, progress, skills) -> {
            saved.add((int) character.getHp()); writing.countDown(); waitFor(release);
        };
        try (var queue = queue(repo); var execution = new GameExecution("snapshot-owner", new DefaultVersionGate())) {
            PlayerCharacter character = new GameplayTestSession(1, new MapleMap()).character;
            execution.run(() -> character.bindExecution(execution));
            execution.run(() -> queue.save(character));
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            execution.submit(() -> { character.setHp(20); queue.save(character); return null; }).get(5, TimeUnit.SECONDS);
            release.countDown(); queue.drain();
            assertThat(saved).containsExactly(50, 20);
            assertThat(character.isDirty()).isFalse();
        } finally { release.countDown(); }
    }

    @Test public void boundStateAndEscapedItemsRejectForeignWrites() {
        try (var execution = new GameExecution("state-owner", new DefaultVersionGate())) {
            PlayerCharacter character = new PlayerCharacter(1);
            Item item = new Item(2000000);
            execution.run(() -> {
                character.getInventory(InventoryType.USE).addItem(item);
                character.bindExecution(execution);
            });
            assertThatThrownBy(() -> character.setHp(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> character.setStrStat((short) 999)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> character.getInventory(InventoryType.USE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> item.setQuantity((short) 99)).isInstanceOf(IllegalStateException.class);
            assertThat(execution.call(character::getHp)).isEqualTo(50);
            assertThat(execution.call(item::getQuantity)).isEqualTo((short) 1);
        }
    }

    @Test public void overlappingInvitationsAndLateConfirmationCannotEscapeTradeLifecycle() {
        var versions = new DefaultVersionGate();
        var sessions = new PlayerSessionRegistry();
        var operations = new EntityReloadCoordinator();
        var system = new DefaultTradeSystem(versions, new DefaultItemSystem(versions, GameDataProvider.fixed(Map.of(), Map.of())));
        var handler = new PlayerInteractionHandler(system, sessions, operations);
        MapleMap map = new MapleMap();
        var first = new GameplayTestSession(1, map); var second = new GameplayTestSession(2, map);
        var third = new GameplayTestSession(3, map);
        sessions.claim(1, first); sessions.claim(2, second); sessions.claim(3, third);
        invite(handler, first, 2);
        Trade trade = first.getAttr("trade");
        invite(handler, third, 2);
        assertThat(second.<Trade>getAttr("trade")).isSameAs(trade);
        assertThat(third.<Trade>getAttr("trade")).isNull();
        assertThat(system.confirm(trade, first.character)).isEqualTo(TradeSystem.ConfirmResult.WAITING);
        handler.handle(first, new ByteArrayInPacket(new byte[]{10}));
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
        assertThat(system.confirm(trade, second.character)).isEqualTo(TradeSystem.ConfirmResult.REJECTED);
        assertThat(second.<Trade>getAttr("trade")).isNull();
        assertThat(operations.inFlightCount()).isZero();
    }

    @Test public void realReloadCancelsTradeAndPreservesExistingPlayerUsability() {
        var versions = new DefaultVersionGate();
        try (var execution = new GameExecution("reload-owner", versions)) {
            var sessions = new PlayerSessionRegistry(execution);
            var operations = new EntityReloadCoordinator();
            var items = new DefaultItemSystem(versions, GameDataProvider.fixed(Map.of(), Map.of()));
            var handler = new PlayerInteractionHandler(new DefaultTradeSystem(versions, items), sessions, operations);
            MapleMap map = new MapleMap();
            var first = new GameplayTestSession(1, map); var second = new GameplayTestSession(2, map);
            execution.run(() -> {
                first.character.bindExecution(execution); second.character.bindExecution(execution);
                sessions.claim(1, first); sessions.claim(2, second); invite(handler, first, 2);
            });
            new EntityReloadService(operations, versions).reloadAllInFlight();
            assertThat(operations.inFlightCount()).isZero();
            assertThat(execution.call(() -> first.<Trade>getAttr("trade"))).isNull();
            assertThat(execution.call(() -> items.changeMeso(first.character, 10))).isTrue();
            assertThat(execution.continueAt(1, () -> items.changeMeso(first.character, 100)).join()).isFalse();
            assertThat(execution.call(first.character::getMeso)).isEqualTo(10);
        }
    }

    @Test public void handlerReplacementWaitsForTheCurrentOperationAndPrecedesNewPackets() throws Exception {
        var versions = new DefaultVersionGate();
        try (var execution = new GameExecution("packet-owner", versions)) {
            HandlerRegistry registry = new HandlerRegistry(execution);
            var session = new GameplayTestSession(1, new MapleMap());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            List<String> events = new CopyOnWriteArrayList<>();
            registry.register(RecvOpcode.MOVE_PLAYER, (s, p) -> events.add("old"));
            var blocker = execution.submit(() -> { entered.countDown(); waitFor(release); return null; });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var replacing = CompletableFuture.runAsync(() -> registry.replace(RecvOpcode.MOVE_PLAYER,
                    (s, p) -> { execution.requireOwner(); events.add("new"); }, 2));
            await().atMost(Duration.ofSeconds(5)).until(() -> execution.queuedTasks() == 1);
            assertThat(replacing.isDone()).isFalse();
            registry.dispatch(RecvOpcode.MOVE_PLAYER.getValue(), session, new ByteArrayInPacket(new byte[0]));
            registry.disconnect(() -> events.add("disconnect"));
            release.countDown(); blocker.get(5, TimeUnit.SECONDS); replacing.get(5, TimeUnit.SECONDS);
            execution.run(() -> { });
            assertThat(events).containsExactly("new", "disconnect");
        }
    }

    @Test public void failedSaveLeavesPlayerInSourceChannel() throws Exception {
        var versions = new DefaultVersionGate();
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger transfers = new AtomicInteger();
        IntercoordService directory = (IntercoordService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IntercoordService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("channel")) return Optional.of(new ChannelDirectoryService.ChannelInfo(2, "127.0.0.1", 8586, 0));
                    if (method.getName().equals("beginChannelTransfer")) transfers.incrementAndGet();
                    return null;
                });
        try (var execution = new GameExecution("transfer-owner", versions); var background = new ThreadManager();
             var queue = queue((character, items, quests, progress, skills) -> {
                 if (failing.get()) throw new IllegalStateException("测试数据库不可用");
             })) {
            var sessions = new PlayerSessionRegistry(execution); var players = new PlayerStorage(execution);
            MapleMap map = new MapleMap(); var session = new GameplayTestSession(1, map);
            execution.run(() -> { players.add(session.character); sessions.claim(1, session); });
            var handler = new ChangeChannelHandler(1, directory, null, sessions, players, queue, background);
            ByteArrayOutPacket packet = new ByteArrayOutPacket(); packet.writeInt(0); packet.writeByte(1);
            execution.run(() -> handler.handle(session, new ByteArrayInPacket(packet.getBytes())));
            await().atMost(Duration.ofSeconds(5)).until(() -> execution.call(() -> session.getAttr("stateTransfer") == null));
            assertThat(execution.call(() -> sessions.get(1))).isSameAs(session);
            assertThat(execution.call(() -> players.getById(1))).isSameAs(session.character);
            assertThat(session.stage()).isEqualTo(SessionStage.IN_GAME);
            assertThat(map.characters()).contains(session.character);
            assertThat(transfers.get()).isZero();
            failing.set(false); queue.drain();
        } finally { failing.set(false); }
    }

    private static CharacterSaveQueue queue(PlayerCharacterSnapshotRepository repo) {
        return new CharacterSaveQueue(repo, new PlayerCharacterAssembler(new DefaultVersionGate()), new PlayerStorage());
    }
    private static void invite(PlayerInteractionHandler handler, GameplayTestSession from, int target) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket(); packet.writeByte(2); packet.writeInt(target);
        handler.handle(from, new ByteArrayInPacket(packet.getBytes()));
    }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
