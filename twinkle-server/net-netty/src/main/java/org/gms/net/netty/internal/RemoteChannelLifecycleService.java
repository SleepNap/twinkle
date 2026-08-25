package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.service.channel.ChannelLifecycleService;
import org.gms.service.intercoord.IntercoordService;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 管理进程侧频道生命周期网络桩；按频道 ID 经 coordinator 路由到常驻频道工作进程。 */
@Log4j2
public final class RemoteChannelLifecycleService implements ChannelLifecycleService {

    private final CoordinatorLink link;
    private final IntercoordService intercoordService;
    private final long timeoutMillis;

    public RemoteChannelLifecycleService(CoordinatorLink link, IntercoordService intercoordService) {
        this(link, intercoordService, 3000);
    }

    public RemoteChannelLifecycleService(CoordinatorLink link, IntercoordService intercoordService,
                                         long timeoutMillis) {
        this.link = link;
        this.intercoordService = intercoordService;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<Status> statuses() {
        List<IntercoordService.ChannelInfo> channels = intercoordService.channels().values().stream()
                .sorted(Comparator.comparingInt(IntercoordService.ChannelInfo::channelId))
                .toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Status>> futures = channels.stream()
                    .map(info -> executor.submit(() -> status(info)))
                    .toList();
            return futures.stream().map(RemoteChannelLifecycleService::completed).toList();
        }
    }

    @Override
    public CommandResult requestStart(int channelId) {
        return command(channelId, ChannelLifecycleRpcDispatcher.START_METHOD);
    }

    @Override
    public CommandResult requestStop(int channelId) {
        return command(channelId, ChannelLifecycleRpcDispatcher.STOP_METHOD);
    }

    @Override
    public CommandResult requestStop(int channelId, boolean force) {
        return command(channelId, ChannelLifecycleRpcDispatcher.STOP_METHOD, force);
    }

    @Override
    public CommandResult requestTerminate(int channelId) {
        return command(channelId, ChannelLifecycleRpcDispatcher.TERMINATE_METHOD);
    }

    @Override
    public CommandResult requestTerminate(int channelId, boolean force) {
        return command(channelId, ChannelLifecycleRpcDispatcher.TERMINATE_METHOD, force);
    }

    private Status status(IntercoordService.ChannelInfo info) {
        InternalProtocol.RpcResponse response = rpc(info.channelId(),
                ChannelLifecycleRpcDispatcher.STATUS_METHOD);
        if (response == null || !response.ok()) {
            return unavailable(info, response == null ? null : response.error());
        }
        Status remote = JsonCodec.decode(response.value(), Status.class.getName());
        if (remote == null) {
            return unavailable(info, null);
        }
        return new Status(info.channelId(), info.host(), info.port(), info.onlineCount(),
                remote.state(), Topology.DISTRIBUTED, true, remote.error());
    }

    private CommandResult command(int channelId, String method, Object... arguments) {
        IntercoordService.ChannelInfo info = intercoordService.channel(channelId).orElse(null);
        if (info == null) {
            return new CommandResult(false, new Status(channelId, "", 0, 0,
                    State.UNAVAILABLE, Topology.DISTRIBUTED, false,
                    I18n.message("error.channel.lifecycle.unknown", channelId)));
        }
        Object[] rpcArguments = new Object[arguments.length + 1];
        rpcArguments[0] = channelId;
        System.arraycopy(arguments, 0, rpcArguments, 1, arguments.length);
        InternalProtocol.RpcResponse response = rpc(channelId, method, rpcArguments);
        if (response == null || !response.ok()) {
            return new CommandResult(false, unavailable(info, response == null ? null : response.error()));
        }
        CommandResult result = JsonCodec.decode(response.value(), CommandResult.class.getName());
        if (result == null || result.status() == null) {
            return new CommandResult(false, unavailable(info, null));
        }
        Status remote = result.status();
        Status merged = new Status(info.channelId(), info.host(), info.port(), remote.onlineCount(),
                remote.state(), Topology.DISTRIBUTED, true, remote.error());
        return new CommandResult(result.accepted(), merged);
    }

    private Status unavailable(IntercoordService.ChannelInfo info, String error) {
        return new Status(info.channelId(), info.host(), info.port(), info.onlineCount(),
                State.UNAVAILABLE, Topology.DISTRIBUTED, false,
                error == null || error.isBlank()
                        ? I18n.message("error.channel.lifecycle.worker_unavailable", info.channelId())
                        : error);
    }

    private InternalProtocol.RpcResponse rpc(int channelId, String method, Object... args) {
        InternalConnection connection = link.connection();
        if (connection == null) {
            return null;
        }
        try {
            String[] encodedArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                encodedArgs[i] = args[i] == null ? null : JsonCodec.encode(args[i]);
            }
            String routedMethod = "channel:" + channelId + ":" + method;
            InternalProtocol.RpcRequest request = new InternalProtocol.RpcRequest(routedMethod, encodedArgs);
            DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.RPC,
                    connection.nextMessageId(), JsonCodec.encode(request));
            CompletableFuture<InternalFrame> future = connection.request(frame);
            InternalFrame reply = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return JsonCodec.decode(reply.payloadText(), InternalProtocol.RpcResponse.class.getName());
        } catch (Exception e) {
            log.warn(I18n.message("log.admin.rpc_failed"), method);
            return null;
        }
    }

    private static Status completed(Future<Status> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
