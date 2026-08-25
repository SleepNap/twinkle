package org.gms.net.netty.internal;

import org.gms.coordinator.ChannelRegistry;
import org.gms.coordinator.CoordinatorService;
import org.gms.coordinator.LocationTable;
import org.gms.coordinator.SingleOwnerStore;
import org.gms.event.InProcessEventBus;
import org.gms.service.channel.ChannelLifecycleService;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteChannelLifecycleServiceIntegrationTest {

    @Test
    void coordinatorRoutesLifecycleCommandsToPersistentChannelWorker() throws Exception {
        IntercoordService truth = new CoordinatorService(
                new LocationTable(), new ChannelRegistry(), new SingleOwnerStore());
        CoordinatorFrameRouter router = new CoordinatorFrameRouter(
                new ChannelConnectionRegistry(), truth, new InProcessEventBus());
        InternalServer server = new InternalServer(router.connectionHandler());
        server.start(0);

        AtomicReference<ChannelLifecycleService.State> state =
                new AtomicReference<>(ChannelLifecycleService.State.RUNNING);
        AtomicBoolean forced = new AtomicBoolean();
        ChannelLifecycleRpcDispatcher dispatcher = new ChannelLifecycleRpcDispatcher(service(state, forced));
        CoordinatorLink channelLink = new CoordinatorLink(
                new InetSocketAddress("127.0.0.1", server.boundPort()), 50);
        channelLink.addConnectListener(connection -> {
            connection.onRpcRequest(envelope -> connection.replyRpc(envelope.messageId(),
                    dispatcher.dispatch(envelope.request().method(), envelope.request().args())));
            connection.send(new DefaultInternalFrame(InternalFrame.MessageType.REGISTER,
                    connection.nextMessageId(), JsonCodec.encode(
                    new InternalProtocol.RegisterPayload(3, "10.0.0.3", 8586, false, 4))));
        });

        CoordinatorLink managementLink = new CoordinatorLink(
                new InetSocketAddress("127.0.0.1", server.boundPort()), 50);
        managementLink.addConnectListener(connection -> connection.send(new DefaultInternalFrame(
                InternalFrame.MessageType.REGISTER, connection.nextMessageId(), JsonCodec.encode(
                new InternalProtocol.RegisterPayload(0, "127.0.0.1", 0, true, 0)))));
        channelLink.start();
        managementLink.start();

        try {
            await(() -> truth.channel(3).isPresent());
            RemoteChannelLifecycleService remote = new RemoteChannelLifecycleService(
                    managementLink, truth, 1000);

            assertThat(remote.statuses()).singleElement().satisfies(status -> {
                assertThat(status.channelId()).isEqualTo(3);
                assertThat(status.state()).isEqualTo(ChannelLifecycleService.State.RUNNING);
                assertThat(status.topology()).isEqualTo(ChannelLifecycleService.Topology.DISTRIBUTED);
            });

            assertThat(remote.requestStop(3, true).accepted()).isTrue();
            assertThat(forced).isTrue();
            forced.set(false);
            assertThat(remote.statuses().getFirst().state())
                    .isEqualTo(ChannelLifecycleService.State.STOPPED);
            assertThat(remote.requestStart(3).accepted()).isTrue();
            assertThat(remote.statuses().getFirst().state())
                    .isEqualTo(ChannelLifecycleService.State.RUNNING);
            assertThat(remote.requestTerminate(3, true).accepted()).isTrue();
            assertThat(forced).isTrue();
            assertThat(remote.statuses().getFirst().state())
                    .isEqualTo(ChannelLifecycleService.State.TERMINATING);
        } finally {
            managementLink.close();
            channelLink.close();
            server.close();
        }
    }

    private static ChannelLifecycleService service(
            AtomicReference<ChannelLifecycleService.State> state, AtomicBoolean forced) {
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
                state.set(State.STOPPED);
                return new CommandResult(true, status());
            }

            @Override
            public CommandResult requestStop(int channelId, boolean force) {
                forced.set(force);
                return requestStop(channelId);
            }

            @Override
            public CommandResult requestTerminate(int channelId) {
                state.set(State.TERMINATING);
                return new CommandResult(true, status());
            }

            @Override
            public CommandResult requestTerminate(int channelId, boolean force) {
                forced.set(force);
                return requestTerminate(channelId);
            }

            private Status status() {
                return new Status(3, "10.0.0.3", 8586, 4, state.get(),
                        Topology.DISTRIBUTED, true, null);
            }
        };
    }

    private interface BoolSupplier {
        boolean get() throws Exception;
    }

    private static void await(BoolSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for channel registration");
    }
}
