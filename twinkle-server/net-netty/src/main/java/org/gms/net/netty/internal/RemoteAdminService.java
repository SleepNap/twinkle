package org.gms.net.netty.internal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 管理进程侧 AdminService 网络桩（架构 4.6.6 第②路：事务性操作经 service 接口 RPC 到频道）。
 *
 * <p>管理进程（http-api / ai）经 {@link AdminService} 访问频道在线状态/运维操作；split 下
 * 管理进程与频道进程分离，本桩把调用编码为 RPC 帧（目标频道前缀）经 coordinator 路由到
 * 对应频道进程（频道进程侧 {@code ChannelAdminService} 处理，见 SplitConfig 接线）。
 *
 * <p>只暴露纯 DTO（接口 {@code OnlinePlayer} 等），不泄漏游戏内存对象（红线 4.1）。
 * 频道未连接时降级：在线快照空、kick 返回 false、脚本重载 0。
 */
public final class RemoteAdminService implements AdminService {

    private static final Logger LOG = LogManager.getLogger(RemoteAdminService.class);

    private final CoordinatorLink link;
    private final int channelId;
    private final long timeoutMillis;

    public RemoteAdminService(CoordinatorLink link, int channelId) {
        this(link, channelId, 3000);
    }

    public RemoteAdminService(CoordinatorLink link, int channelId, long timeoutMillis) {
        this.link = link;
        this.channelId = channelId;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public ChannelSummary onlineSummary() {
        // RPC 到本频道：onlineSummary
        InternalProtocol.RpcResponse resp = rpc("onlineSummary");
        if (resp == null || !resp.ok()) {
            return new ChannelSummary(0, channelId, List.of());
        }
        ChannelSummary summary = JsonCodec.decode(resp.value(), ChannelSummary.class.getName());
        return summary == null ? new ChannelSummary(0, channelId, List.of()) : summary;
    }

    @Override
    public boolean kick(long characterId) {
        InternalProtocol.RpcResponse resp = rpc("kick", characterId);
        if (resp == null || !resp.ok()) {
            return false;
        }
        Boolean b = JsonCodec.decode(resp.value(), Boolean.class.getName());
        return b != null && b;
    }

    @Override
    public int reloadScripts() {
        InternalProtocol.RpcResponse resp = rpc("reloadScripts");
        if (resp == null || !resp.ok()) {
            return 0;
        }
        Integer n = JsonCodec.decode(resp.value(), Integer.class.getName());
        return n == null ? 0 : n;
    }

    @Override
    public void requestRestart() {
        rpcVoid("requestRestart");
    }

    @Override
    public RestartCoordinator.Phase restartPhase() {
        InternalProtocol.RpcResponse resp = rpc("restartPhase");
        if (resp == null || !resp.ok()) {
            return RestartCoordinator.Phase.RUNNING;
        }
        RestartCoordinator.Phase phase = JsonCodec.decode(resp.value(), RestartCoordinator.Phase.class.getName());
        return phase == null ? RestartCoordinator.Phase.RUNNING : phase;
    }

    // ---- RPC 基础设施（目标频道前缀，coordinator 转发到本频道进程） ----

    private void rpcVoid(String method, Object... args) {
        rpc(method, args);
    }

    private InternalProtocol.RpcResponse rpc(String method, Object... args) {
        InternalConnection conn = link.connection();
        if (conn == null) {
            LOG.warn("AdminService RPC 时 coordinator 未连接: method={}", method);
            return null;
        }
        try {
            String[] encodedArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                encodedArgs[i] = args[i] == null ? null : JsonCodec.encode(args[i]);
            }
            // 目标频道前缀：coordinator 路由到本频道进程（channel:{id}:method）
            String routedMethod = "channel:" + channelId + ":" + method;
            InternalProtocol.RpcRequest req = new InternalProtocol.RpcRequest(routedMethod, encodedArgs);
            DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.RPC,
                    conn.nextMessageId(), JsonCodec.encode(req));
            CompletableFuture<InternalFrame> fut = conn.request(frame);
            InternalFrame reply = fut.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return JsonCodec.decode(reply.payloadText(), InternalProtocol.RpcResponse.class.getName());
        } catch (Exception e) {
            LOG.warn("AdminService RPC 失败: method={}", method);
            return null;
        }
    }
}
