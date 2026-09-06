package org.gms.net.netty.internal;

import org.gms.service.channel.ChannelLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelLifecycleRpcDispatcherTest {

    @Test
    void dispatchesStatusAndLifecycleCommands() {
        AtomicBoolean forced = new AtomicBoolean();
        ChannelLifecycleRpcDispatcher dispatcher = new ChannelLifecycleRpcDispatcher(service(forced));

        InternalProtocol.RpcResponse statusResponse = dispatcher.dispatch(
                ChannelLifecycleRpcDispatcher.STATUS_METHOD, new String[0]);
        InternalProtocol.RpcResponse stopResponse = dispatcher.dispatch(
                ChannelLifecycleRpcDispatcher.STOP_METHOD,
                new String[]{JsonCodec.encode(2), JsonCodec.encode(true)});
        assertThat(statusResponse.ok()).isTrue();
        ChannelLifecycleService.Status status = JsonCodec.decode(
                statusResponse.value(), ChannelLifecycleService.Status.class.getName());
        assertThat(status.channelId()).isEqualTo(2);
        assertThat(status.state()).isEqualTo(ChannelLifecycleService.State.RUNNING);

        assertThat(stopResponse.ok()).isTrue();
        ChannelLifecycleService.CommandResult result = JsonCodec.decode(
                stopResponse.value(), ChannelLifecycleService.CommandResult.class.getName());
        assertThat(result.accepted()).isTrue();
        assertThat(result.status().state()).isEqualTo(ChannelLifecycleService.State.STOPPING);
        assertThat(forced).isTrue();
        forced.set(false);

        InternalProtocol.RpcResponse terminateResponse = dispatcher.dispatch(
                ChannelLifecycleRpcDispatcher.TERMINATE_METHOD,
                new String[]{JsonCodec.encode(2), JsonCodec.encode(true)});
        assertThat(terminateResponse.ok()).isTrue();
        assertThat(forced).isTrue();
    }

    @Test
    void selectsStatusForTargetChannelInMultiChannelWorker() {
        ChannelLifecycleService service = new ChannelLifecycleService() {
            @Override
            public List<Status> statuses() {
                return List.of(
                        new Status(11, "127.0.0.1", 8584, 3, State.RUNNING,
                                Topology.DISTRIBUTED, true, null),
                        new Status(12, "127.0.0.1", 8585, 0, State.STOPPED,
                                Topology.DISTRIBUTED, true, null));
            }

            @Override public CommandResult requestStart(int channelId) { return null; }
            @Override public CommandResult requestStop(int channelId) { return null; }
            @Override public CommandResult requestTerminate(int channelId) { return null; }
        };
        ChannelLifecycleRpcDispatcher dispatcher = new ChannelLifecycleRpcDispatcher(service);

        InternalProtocol.RpcResponse response = dispatcher.dispatch(
                ChannelLifecycleRpcDispatcher.STATUS_METHOD, new String[0], 12);
        ChannelLifecycleService.Status selected = JsonCodec.decode(
                response.value(), ChannelLifecycleService.Status.class.getName());

        assertThat(response.ok()).isTrue();
        assertThat(selected.channelId()).isEqualTo(12);
        assertThat(selected.state()).isEqualTo(ChannelLifecycleService.State.STOPPED);
    }

    private static ChannelLifecycleService service(AtomicBoolean forced) {
        return new ChannelLifecycleService() {
            @Override
            public List<Status> statuses() {
                return List.of(status(State.RUNNING));
            }

            @Override
            public CommandResult requestStart(int channelId) {
                return new CommandResult(true, status(State.STARTING));
            }

            @Override
            public CommandResult requestStop(int channelId) {
                return new CommandResult(true, status(State.STOPPING));
            }

            @Override
            public CommandResult requestStop(int channelId, boolean force) {
                forced.set(force);
                return requestStop(channelId);
            }

            @Override
            public CommandResult requestTerminate(int channelId) {
                return new CommandResult(true, status(State.TERMINATING));
            }

            @Override
            public CommandResult requestTerminate(int channelId, boolean force) {
                forced.set(force);
                return requestTerminate(channelId);
            }

            private Status status(State state) {
                return new Status(2, "10.0.0.2", 8585, 7, state, Topology.DISTRIBUTED, true, null);
            }
        };
    }
}
