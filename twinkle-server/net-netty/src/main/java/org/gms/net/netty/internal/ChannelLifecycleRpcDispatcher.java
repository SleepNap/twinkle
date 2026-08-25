package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.service.channel.ChannelLifecycleService;

/** 频道进程侧生命周期 RPC 分发器。 */
@Log4j2
public final class ChannelLifecycleRpcDispatcher {

    public static final String STATUS_METHOD = "channelLifecycleStatus";
    public static final String START_METHOD = "channelLifecycleStart";
    public static final String STOP_METHOD = "channelLifecycleStop";
    public static final String TERMINATE_METHOD = "channelLifecycleTerminate";

    private final ChannelLifecycleService lifecycleService;

    public ChannelLifecycleRpcDispatcher(ChannelLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    public boolean supports(String method) {
        return STATUS_METHOD.equals(method) || START_METHOD.equals(method) || STOP_METHOD.equals(method)
                || TERMINATE_METHOD.equals(method);
    }

    public InternalProtocol.RpcResponse dispatch(String method, String[] args) {
        try {
            return switch (method) {
                case STATUS_METHOD -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(
                        lifecycleService.statuses().stream().findFirst().orElse(null)));
                case START_METHOD -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(
                        lifecycleService.requestStart(intArg(args, 0))));
                case STOP_METHOD -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(
                        lifecycleService.requestStop(intArg(args, 0), booleanArg(args, 1))));
                case TERMINATE_METHOD -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(
                        lifecycleService.requestTerminate(intArg(args, 0), booleanArg(args, 1))));
                default -> InternalProtocol.RpcResponse.fail(I18n.message("error.rpc.unknown_admin_method", method));
            };
        } catch (Exception e) {
            log.error(I18n.message("log.rpc.admin_dispatch_failed"), method, e);
            return InternalProtocol.RpcResponse.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int intArg(String[] args, int index) {
        return args == null || index >= args.length ? 0
                : JsonCodec.decode(args[index], Integer.class.getName());
    }

    private static boolean booleanArg(String[] args, int index) {
        return args != null && index < args.length
                && Boolean.TRUE.equals(JsonCodec.decode(args[index], Boolean.class.getName()));
    }
}
