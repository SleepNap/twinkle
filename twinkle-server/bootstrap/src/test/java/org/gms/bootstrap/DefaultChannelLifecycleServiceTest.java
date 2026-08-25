package org.gms.bootstrap;

import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.entity.Character;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.packet.HandlerRegistry;
import org.gms.service.channel.ChannelLifecycleService;
import org.gms.tick.GameTickLoop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DefaultChannelLifecycleServiceTest {

    @Test
    void stopsAndStartsEmbeddedChannelWithoutStoppingControlProcess() {
        PlayerStorage players = new PlayerStorage();
        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repository(),
                new CharacterLoader(new DefaultVersionGate()), players);
        ChannelServer server = new ChannelServer(new HandlerRegistry());
        server.start(0);
        int port = server.boundPort();
        RestartService restartService = new RestartService(new RestartCoordinator(), new GameTickLoop(5),
                new EntityReloadService(new EntityReloadCoordinator(), new DefaultVersionGate()), saveQueue);
        AtomicBoolean exited = new AtomicBoolean();
        DefaultChannelLifecycleService service = new DefaultChannelLifecycleService(
                server, players, restartService, 1, "127.0.0.1", port, "", () -> exited.set(true));

        try {
            assertThat(service.statuses()).singleElement().satisfies(status -> {
                assertThat(status.state()).isEqualTo(ChannelLifecycleService.State.RUNNING);
                assertThat(status.topology()).isEqualTo(ChannelLifecycleService.Topology.EMBEDDED);
            });

            assertThat(service.requestStop(1).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(server.isRunning()).isFalse();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.STOPPED);
            });

            assertThat(service.requestStart(1).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(server.isRunning()).isTrue();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.RUNNING);
            });

            assertThat(service.requestTerminate(1).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(exited).isTrue();
                assertThat(server.isRunning()).isFalse();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.STOPPED);
            });
        } finally {
            server.stop();
            saveQueue.close();
        }
    }

    @Test
    void gracefulStopWaitsForInFlightTasksWhileForcedStopInterruptsThem() throws Exception {
        PlayerStorage players = new PlayerStorage();
        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repository(),
                new CharacterLoader(new DefaultVersionGate()), players);
        ChannelServer server = new ChannelServer(new HandlerRegistry());
        server.start(0);
        int port = server.boundPort();
        EntityReloadCoordinator operations = new EntityReloadCoordinator();
        RestartService restartService = new RestartService(new RestartCoordinator(), new GameTickLoop(5),
                new EntityReloadService(operations, new DefaultVersionGate()), saveQueue);
        DefaultChannelLifecycleService service = new DefaultChannelLifecycleService(
                server, players, restartService, 1, "127.0.0.1", port, "", () -> { });

        try {
            operations.beginOperation(42L);
            assertThat(service.requestStop(1, false).accepted()).isTrue();
            Thread.sleep(100);
            assertThat(server.isRunning()).isTrue();
            assertThat(service.statuses().getFirst().state()).isEqualTo(ChannelLifecycleService.State.STOPPING);

            operations.endOperation(42L);
            await().untilAsserted(() -> {
                assertThat(server.isRunning()).isFalse();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.STOPPED);
            });

            assertThat(service.requestStart(1).accepted()).isTrue();
            await().untilAsserted(() -> assertThat(server.isRunning()).isTrue());

            operations.beginOperation(43L);
            assertThat(service.requestStop(1, true).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(server.isRunning()).isFalse();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.STOPPED);
            });
        } finally {
            server.stop();
            saveQueue.close();
        }
    }

    @Test
    void persistenceFailureBlocksNormalExitButForcedExitContinues() {
        AtomicBoolean databaseAvailable = new AtomicBoolean(false);
        CharacterRepository repository = new CharacterRepository() {
            @Override
            public List<Character> findByAccount(int accountId, int world) {
                return List.of();
            }

            @Override
            public Optional<Character> findById(long id) {
                return Optional.empty();
            }

            @Override
            public boolean existsByName(String name) {
                return false;
            }

            @Override
            public void insert(Character character) {
            }

            @Override
            public void save(Character character) {
                if (!databaseAvailable.get()) {
                    throw new IllegalStateException("database unavailable");
                }
            }
        };
        PlayerStorage players = new PlayerStorage();
        org.gms.domain.game.Character player = new org.gms.domain.game.Character(
                new DefaultVersionGate().currentVersion());
        player.setId(29L);
        player.setName("SafeShutdown");
        player.setMap(100000000);
        player.markDirty();
        players.add(player);

        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repository,
                new CharacterLoader(new DefaultVersionGate()), players);
        ChannelServer server = new ChannelServer(new HandlerRegistry());
        server.start(0);
        RestartService restartService = new RestartService(new RestartCoordinator(), new GameTickLoop(5),
                new EntityReloadService(new EntityReloadCoordinator(), new DefaultVersionGate()), saveQueue);
        AtomicBoolean exited = new AtomicBoolean();
        DefaultChannelLifecycleService service = new DefaultChannelLifecycleService(
                server, players, restartService, 1, "127.0.0.1", server.boundPort(), "channel",
                () -> exited.set(true));

        try {
            assertThat(service.requestTerminate(1).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.FAILED);
                assertThat(exited).isFalse();
            });

            assertThat(service.requestTerminate(1, true).accepted()).isTrue();
            await().untilAsserted(() -> {
                assertThat(exited).isTrue();
                assertThat(service.statuses().getFirst().state())
                        .isEqualTo(ChannelLifecycleService.State.STOPPED);
            });
        } finally {
            server.stop();
            saveQueue.close();
        }
    }

    private static CharacterRepository repository() {
        return new CharacterRepository() {
            @Override
            public List<Character> findByAccount(int accountId, int world) {
                return List.of();
            }

            @Override
            public Optional<Character> findById(long id) {
                return Optional.empty();
            }

            @Override
            public boolean existsByName(String name) {
                return false;
            }

            @Override
            public void insert(Character character) {
            }

            @Override
            public void save(Character character) {
            }
        };
    }
}
