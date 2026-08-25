package org.gms.bootstrap;

import org.gms.service.channel.ChannelLifecycleService;
import org.gms.service.shutdown.ClusterShutdownService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DefaultClusterShutdownServiceTest {

    @Test
    void drainsAndTerminatesDistributedWorkersBeforeCoordinator() {
        AtomicReference<ChannelLifecycleService.State> channelState =
                new AtomicReference<>(ChannelLifecycleService.State.RUNNING);
        AtomicBoolean loginStopped = new AtomicBoolean();
        AtomicBoolean workerTerminated = new AtomicBoolean();
        AtomicBoolean coordinatorExited = new AtomicBoolean();
        DefaultClusterShutdownService service = new DefaultClusterShutdownService(
                lifecycle(channelState, workerTerminated), () -> loginStopped.set(true), 1000,
                () -> coordinatorExited.set(true));

        assertThat(service.requestShutdown().accepted()).isTrue();

        await().untilAsserted(() -> {
            assertThat(loginStopped).isTrue();
            assertThat(workerTerminated).isTrue();
            assertThat(coordinatorExited).isTrue();
            assertThat(service.status().phase())
                    .isEqualTo(ClusterShutdownService.Phase.STOPPING_COORDINATOR);
        });
    }

    @Test
    void keepsCoordinatorAliveAndReportsUnreachableWorkers() {
        AtomicBoolean coordinatorExited = new AtomicBoolean();
        ChannelLifecycleService unavailable = lifecycle(
                new AtomicReference<>(ChannelLifecycleService.State.UNAVAILABLE), new AtomicBoolean());
        DefaultClusterShutdownService service = new DefaultClusterShutdownService(
                unavailable, () -> { }, 50, () -> coordinatorExited.set(true));

        assertThat(service.requestShutdown().accepted()).isTrue();

        await().untilAsserted(() -> {
            assertThat(service.status().phase())
                    .isEqualTo(ClusterShutdownService.Phase.PARTIAL_FAILURE);
            assertThat(service.status().failedChannelIds()).containsExactly(1);
            assertThat(coordinatorExited).isFalse();
        });
    }

    @Test
    void forcedShutdownContinuesPastUnreachableWorker() {
        AtomicBoolean coordinatorExited = new AtomicBoolean();
        ChannelLifecycleService unavailable = lifecycle(
                new AtomicReference<>(ChannelLifecycleService.State.UNAVAILABLE), new AtomicBoolean());
        DefaultClusterShutdownService service = new DefaultClusterShutdownService(
                unavailable, () -> { }, 50, () -> coordinatorExited.set(true));

        assertThat(service.requestShutdown(true).accepted()).isTrue();

        await().untilAsserted(() -> {
            assertThat(service.status().phase())
                    .isEqualTo(ClusterShutdownService.Phase.STOPPING_COORDINATOR);
            assertThat(service.status().failedChannelIds()).containsExactly(1);
            assertThat(coordinatorExited).isTrue();
        });
    }

    @Test
    void normalApplicationStopUsesSameDrainProtocolWithoutExitingAgain() {
        AtomicReference<ChannelLifecycleService.State> channelState =
                new AtomicReference<>(ChannelLifecycleService.State.RUNNING);
        AtomicBoolean workerTerminated = new AtomicBoolean();
        AtomicBoolean coordinatorExited = new AtomicBoolean();
        DefaultClusterShutdownService service = new DefaultClusterShutdownService(
                lifecycle(channelState, workerTerminated), () -> { }, 1000,
                () -> coordinatorExited.set(true));

        service.shutdownForContextClose();

        assertThat(workerTerminated).isTrue();
        assertThat(coordinatorExited).isFalse();
        assertThat(service.status().phase())
                .isEqualTo(ClusterShutdownService.Phase.STOPPING_COORDINATOR);
    }

    private static ChannelLifecycleService lifecycle(
            AtomicReference<ChannelLifecycleService.State> state, AtomicBoolean terminated) {
        return new ChannelLifecycleService() {
            @Override
            public List<Status> statuses() {
                return List.of(status());
            }

            @Override
            public CommandResult requestStart(int channelId) {
                state.set(State.RUNNING);
                return new CommandResult(true, status());
            }

            @Override
            public CommandResult requestStop(int channelId) {
                if (state.get() == State.UNAVAILABLE) {
                    return new CommandResult(false, status());
                }
                state.set(State.STOPPED);
                return new CommandResult(true, status());
            }

            @Override
            public CommandResult requestTerminate(int channelId) {
                terminated.set(true);
                state.set(State.TERMINATING);
                return new CommandResult(true, status());
            }

            private Status status() {
                boolean controllable = state.get() != State.UNAVAILABLE;
                return new Status(1, "127.0.0.1", 8584, 0, state.get(),
                        Topology.DISTRIBUTED, controllable, null);
            }
        };
    }
}
