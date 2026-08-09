package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.service.intercoord.IntercoordService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网络 IntercoordService 桩（架构 4.5：频道/管理进程侧，方法调用 → RPC 帧 → coordinator 真值）。
 *
 * <p>实现 {@link IntercoordService}（core 接口），每个方法把参数 JSON 序列化、构造 RPC 帧经
 * {@link InternalConnection} 发 coordinator，等 RPC_RESPONSE 后反序列化返回值。
 * 频道侧调用面零变化（铁律 1：接口不假设进程内）。
 *
 * <p>同步阻塞（调用方是游戏 handler 线程，低频控制面可接受）；断链/超时返回降级默认值
 * （定位 unknown、注册失败记日志）——频道本地逻辑不依赖 coordinator（架构 4.5 故障矩阵）。
 */
@Log4j2
public final class RemoteIntercoordService implements IntercoordService {



    private final CoordinatorLink link;
    private final long timeoutMillis;

    public RemoteIntercoordService(CoordinatorLink link) {
        this(link, 3000);
    }

    public RemoteIntercoordService(CoordinatorLink link, long timeoutMillis) {
        this.link = link;
        this.timeoutMillis = timeoutMillis;
    }

    // ---- 定位表 ----

    @Override
    public void registerPlayer(long playerId, int channelId) {
        rpcVoid("registerPlayer", playerId, channelId);
    }

    @Override
    public void unregisterPlayer(long playerId) {
        rpcVoid("unregisterPlayer", playerId);
    }

    @Override
    public void movePlayer(long playerId, int channelId) {
        rpcVoid("movePlayer", playerId, channelId);
    }

    @Override
    public Optional<Integer> locate(long playerId) {
        InternalProtocol.RpcResponse resp = rpc("locate", playerId);
        if (resp == null || !resp.ok() || "null".equals(resp.value())) {
            return Optional.empty();
        }
        Integer channelId = JsonCodec.decode(resp.value(), Integer.class.getName());
        return channelId == null ? Optional.empty() : Optional.of(channelId);
    }

    @Override
    public int onlineOnChannel(int channelId) {
        InternalProtocol.RpcResponse resp = rpc("onlineOnChannel", channelId);
        if (resp == null || !resp.ok()) {
            return 0;
        }
        Integer n = JsonCodec.decode(resp.value(), Integer.class.getName());
        return n == null ? 0 : n;
    }

    // ---- 频道注册 ----

    @Override
    public void registerChannel(int channelId, String host, int port, int onlineCount) {
        rpcVoid("registerChannel", channelId, host, port, onlineCount);
    }

    @Override
    public void heartbeatChannel(int channelId, int onlineCount) {
        rpcVoid("heartbeatChannel", channelId, onlineCount);
    }

    @Override
    public Optional<ChannelInfo> channel(int channelId) {
        InternalProtocol.RpcResponse resp = rpc("channel", channelId);
        if (resp == null || !resp.ok() || "null".equals(resp.value())) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonCodec.decode(resp.value(), ChannelInfo.class.getName()));
    }

    @Override
    public Map<Integer, ChannelInfo> channels() {
        InternalProtocol.RpcResponse resp = rpc("channels");
        if (resp == null || !resp.ok()) {
            return Map.of();
        }
        Map<Integer, ChannelInfo> decoded = JsonCodec.decode(resp.value(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<Integer, ChannelInfo>>() {
                });
        return decoded == null ? Map.of() : Map.copyOf(decoded);
    }

    // ---- 单一属主存储 ----

    @Override
    public Optional<StoreEntry> read(String key) {
        InternalProtocol.RpcResponse resp = rpc("read", key);
        if (resp == null || !resp.ok() || "null".equals(resp.value())) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonCodec.decode(resp.value(), StoreEntry.class.getName()));
    }

    @Override
    public long write(String key, Object value, long expectedVersion) {
        // 值跨进程保持 JSON 字符串（coordinator 端 StoreEntry.value 为 String，读端按需解析）
        InternalProtocol.RpcResponse resp = rpc("write", key, value == null ? null : JsonCodec.encode(value), expectedVersion);
        if (resp == null || !resp.ok()) {
            return -1;
        }
        Long v = JsonCodec.decode(resp.value(), Long.class.getName());
        return v == null ? -1 : v;
    }

    @Override
    public long increment(String key, long delta) {
        InternalProtocol.RpcResponse resp = rpc("increment", key, delta);
        if (resp == null || !resp.ok()) {
            return -1;
        }
        Long v = JsonCodec.decode(resp.value(), Long.class.getName());
        return v == null ? -1 : v;
    }

    @Override
    public Map<String, StoreEntry> storeSnapshot() {
        InternalProtocol.RpcResponse resp = rpc("storeSnapshot");
        if (resp == null || !resp.ok()) {
            return Map.of();
        }
        Map<String, StoreEntry> decoded = JsonCodec.decode(resp.value(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, StoreEntry>>() {
                });
        return decoded == null ? Map.of() : Map.copyOf(decoded);
    }

    // ---- RPC 基础设施 ----

    private void rpcVoid(String method, Object... args) {
        rpc(method, args);
    }

    private InternalProtocol.RpcResponse rpc(String method, Object... args) {
        InternalConnection conn = link.connection();
        if (conn == null) {
            log.warn("RPC 调用时 coordinator 未连接: method={}（降级默认值）", method);
            return null;
        }
        try {
            String[] encodedArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                encodedArgs[i] = args[i] == null ? null : JsonCodec.encode(args[i]);
            }
            InternalProtocol.RpcRequest req = new InternalProtocol.RpcRequest(method, encodedArgs);
            DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.RPC,
                    conn.nextMessageId(), JsonCodec.encode(req));
            CompletableFuture<InternalFrame> fut = conn.request(frame);
            InternalFrame reply = fut.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return JsonCodec.decode(reply.payloadText(), InternalProtocol.RpcResponse.class.getName());
        } catch (Exception e) {
            log.warn("RPC 调用失败: method={}（降级默认值）", method);
            return null;
        }
    }
}
