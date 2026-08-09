package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.service.intercoord.IntercoordService;

import java.util.Optional;

/**
 * IntercoordService RPC 分发器（架构 4.5：coordinator 端处理频道 RPC 帧 → 调进程内真值）。
 *
 * <p>频道进程的 {@link RemoteIntercoordService} 把方法调用编码为 RPC 帧（方法名 + 参数 JSON），
 * coordinator 端本类按方法名分发到进程内 {@link IntercoordService}（single 档即
 * {@code CoordinatorService} 真值），结果序列化回 RPC_RESPONSE 帧。
 *
 * <p>返回值约定：返回值 JSON 字符串；{@code void}/{@code null}/{@code Optional.empty} 一律
 * 返回 {@code "null"}（避免调用方反序列化歧义）。参数每个已 JSON 序列化为字符串。
 */
@Log4j2
public final class IntercoordRpcDispatcher {



    private final IntercoordService intercoord;

    public IntercoordRpcDispatcher(IntercoordService intercoord) {
        this.intercoord = intercoord;
    }

    /**
     * 分发 RPC 请求。
     *
     * @return 成功响应（值 JSON）/失败响应（错误信息）。
     */
    public InternalProtocol.RpcResponse dispatch(String method, String[] args) {
        try {
            String value = invoke(method, args);
            return InternalProtocol.RpcResponse.ok(value);
        } catch (Exception e) {
            log.error("RPC 分发失败: method={}", method, e);
            return InternalProtocol.RpcResponse.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String invoke(String method, String[] args) {
        return switch (method) {
            // ---- 定位表 ----
            case "registerPlayer" -> {
                intercoord.registerPlayer(longArg(args, 0), intArg(args, 1));
                yield "null";
            }
            case "unregisterPlayer" -> {
                intercoord.unregisterPlayer(longArg(args, 0));
                yield "null";
            }
            case "movePlayer" -> {
                intercoord.movePlayer(longArg(args, 0), intArg(args, 1));
                yield "null";
            }
            case "locate" -> {
                Optional<Integer> r = intercoord.locate(longArg(args, 0));
                yield r.map(String::valueOf).orElse("null");
            }
            case "onlineOnChannel" -> String.valueOf(intercoord.onlineOnChannel(intArg(args, 0)));
            // ---- 频道注册 ----
            case "registerChannel" -> {
                intercoord.registerChannel(intArg(args, 0), strArg(args, 1), intArg(args, 2), intArg(args, 3));
                yield "null";
            }
            case "heartbeatChannel" -> {
                intercoord.heartbeatChannel(intArg(args, 0), intArg(args, 1));
                yield "null";
            }
            case "channel" -> {
                Optional<IntercoordService.ChannelInfo> r = intercoord.channel(intArg(args, 0));
                yield r.map(JsonCodec::encode).orElse("null");
            }
            case "channels" -> JsonCodec.encode(intercoord.channels());
            // ---- 单一属主存储 ----
            case "read" -> {
                Optional<IntercoordService.StoreEntry> r = intercoord.read(strArg(args, 0));
                yield r.map(JsonCodec::encode).orElse("null");
            }
            case "write" -> String.valueOf(intercoord.write(strArg(args, 0), objArg(args, 1), longArg(args, 2)));
            case "increment" -> String.valueOf(intercoord.increment(strArg(args, 0), longArg(args, 1)));
            case "storeSnapshot" -> JsonCodec.encode(intercoord.storeSnapshot());
            default -> throw new IllegalArgumentException("未知 RPC 方法: " + method);
        };
    }

    // ---- 参数解析（各参数已 JSON 序列化为字符串） ----

    private static String strArg(String[] args, int i) {
        if (args == null || i >= args.length || args[i] == null) {
            return null;
        }
        String json = args[i];
        if ("null".equals(json)) {
            return null;
        }
        String decoded = JsonCodec.decode(json, String.class.getName());
        return decoded == null ? json : decoded;
    }

    private static int intArg(String[] args, int i) {
        return args == null || i >= args.length ? 0 : JsonCodec.decode(args[i], Integer.class.getName());
    }

    private static long longArg(String[] args, int i) {
        return args == null || i >= args.length ? 0L : JsonCodec.decode(args[i], Long.class.getName());
    }

    /** 单一属主存储的 value 是任意 Object：保持 JSON 字符串（跨进程不重建原类型，读端按需解析）。 */
    private static Object objArg(String[] args, int i) {
        return args == null || i >= args.length ? null : args[i];
    }
}
