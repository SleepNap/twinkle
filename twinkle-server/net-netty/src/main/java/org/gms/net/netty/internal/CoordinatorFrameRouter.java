package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.event.InProcessEventBus;
import org.gms.message.MessageTargets;
import org.gms.service.intercoord.IntercoordService;

import java.util.Map;
import java.util.function.Consumer;

/**
 * coordinator 端内部通信处理器（架构 4.5：注册中心 + 消息路由 + RPC 真值分发）。
 *
 * <p>coordinator 进程装配：{@link InternalServer} 监听 + 本类处理每连接帧：
 * <ul>
 *   <li><b>REGISTER</b>：进程身份上报 → 连接注册表登记（频道连接 / 管理进程）。</li>
 *   <li><b>HEARTBEAT</b>：频道心跳续期 → 进程内 {@link IntercoordService} 真值。</li>
 *   <li><b>EVENT</b>：消息总线投递 → 按 target 路由（channel:N → N 频道；* → 群发；其余 → 管理进程）。</li>
 *   <li><b>RPC</b>：频道方法调用 → 进程内真值分发 → 回响应帧。</li>
 * </ul>
 *
 * <p>星形拓扑：coordinator 是中心路由器（架构 4.5），本类不持任何共享状态——
 * 频道注册/定位真值全在进程内 {@link IntercoordService}（无状态 + 可重建，架构 4.2）。
 */
@Log4j2
public final class CoordinatorFrameRouter {



    private final ChannelConnectionRegistry registry;
    private final IntercoordService intercoord;
    private final InProcessEventBus localEventBus;
    private final IntercoordRpcDispatcher rpc;

    /** 管理进程→频道的转发 RPC：messageId → 来源连接（响应回传用）。 */
    private final java.util.concurrent.ConcurrentMap<Long, InternalConnection> rpcOrigins =
            new java.util.concurrent.ConcurrentHashMap<>();

    public CoordinatorFrameRouter(ChannelConnectionRegistry registry, IntercoordService intercoord,
                                  InProcessEventBus localEventBus) {
        this.registry = registry;
        this.intercoord = intercoord;
        this.localEventBus = localEventBus;
        this.rpc = new IntercoordRpcDispatcher(intercoord);
    }

    /** 每连接注册的帧处理入口（InternalServer connectionHandler 调用）。 */
    public Consumer<InternalConnection> connectionHandler() {
        return conn -> {
            conn.onFrame(frame -> handleFrame(conn, frame));
        };
    }

    /** 进程内 EventBus 订阅者（管理进程/单进程本地的在线事件 → 路由给管理进程镜像）。 */
    public InProcessEventBus localBus() {
        return localEventBus;
    }

    private void handleFrame(InternalConnection conn, InternalFrame frame) {
        switch (frame.type()) {
            case REGISTER -> handleRegister(conn, frame);
            case HEARTBEAT -> handleHeartbeat(conn, frame);
            case EVENT -> handleEvent(conn, frame);
            case RPC -> handleRpc(conn, frame);
            case RPC_RESPONSE -> handleRpcResponse(conn, frame);
            default -> log.warn(I18n.message("log.coordinator.unroutable_frame"), frame.type(), frame.messageId());
        }
    }

    /**
     * RPC 请求路由：
     * <ul>
     *   <li>RPC 目标是本进程真值（频道侧 IntercoordService 调用）→ 本地分发回响应。</li>
     *   <li>RPC 是管理进程→频道（运维操作）→ 转发目标频道连接，频道回响应时本类再转回管理进程
     *       （见 {@link #handleRpcResponse}）。</li>
     * </ul>
     */
    private void handleRpc(InternalConnection conn, InternalFrame frame) {
        InternalProtocol.RpcRequest req = JsonCodec.decode(frame.payloadText(),
                InternalProtocol.RpcRequest.class.getName());
        if (req == null) {
            sendRpcError(conn, frame.messageId(), I18n.message("error.rpc.request_parse_failed"));
            return;
        }
        // 目标频道编码在方法名前缀 "channel:{id}:"（管理进程→频道运维操作）
        if (req.method() != null && req.method().startsWith("channel:")) {
            int colon = req.method().indexOf(':', "channel:".length());
            if (colon < 0) {
                sendRpcError(conn, frame.messageId(), I18n.message("error.rpc.target_channel_format_invalid"));
                return;
            }
            String idStr = req.method().substring("channel:".length(), colon);
            try {
                int channelId = Integer.parseInt(idStr);
                InternalConnection target = registry.channel(channelId);
                if (target == null || !target.isActive()) {
                    sendRpcError(conn, frame.messageId(), I18n.message("error.rpc.target_channel_unconnected", channelId));
                    return;
                }
                // 转发（method 去掉前缀，保留原 messageId 供响应关联）；记住来源连接
                String innerMethod = req.method().substring(colon + 1);
                InternalProtocol.RpcRequest inner = new InternalProtocol.RpcRequest(innerMethod, req.args());
                rpcOrigins.put(frame.messageId(), conn);
                target.send(new DefaultInternalFrame(InternalFrame.MessageType.RPC,
                        frame.messageId(), JsonCodec.encode(inner)));
            } catch (NumberFormatException e) {
                sendRpcError(conn, frame.messageId(), I18n.message("error.rpc.target_channel_invalid", idStr));
            }
            return;
        }
        // 本进程真值分发（频道→coordinator 的 IntercoordService 调用）
        InternalProtocol.RpcResponse resp = rpc.dispatch(req.method(), req.args());
        conn.send(new DefaultInternalFrame(InternalFrame.MessageType.RPC_RESPONSE,
                frame.messageId(), JsonCodec.encode(resp)));
    }

    /** RPC 响应回传：若该 messageId 是从管理进程转发的，则回传来源连接。 */
    private void handleRpcResponse(InternalConnection conn, InternalFrame frame) {
        InternalConnection origin = rpcOrigins.remove(frame.messageId());
        if (origin != null && origin.isActive()) {
            origin.send(frame);
        }
        // 非转发的响应（本地 RPC 的直接响应）已在发起的连接上完成，无需处理
    }

    private void sendRpcError(InternalConnection conn, long messageId, String error) {
        conn.send(new DefaultInternalFrame(InternalFrame.MessageType.RPC_RESPONSE,
                messageId, JsonCodec.encode(InternalProtocol.RpcResponse.fail(error))));
    }

    private void handleRegister(InternalConnection conn, InternalFrame frame) {
        InternalProtocol.RegisterPayload reg = JsonCodec.decode(frame.payloadText(),
                InternalProtocol.RegisterPayload.class.getName());
        if (reg == null) {
            log.warn(I18n.message("log.coordinator.register_parse_failed"));
            return;
        }
        if (reg.admin()) {
            registry.registerAdmin(conn);
            // 注册表里有频道连接时，把已有频道信息回给管理进程（admin 查询 channels 用）
            log.info(I18n.message("log.coordinator.admin_registered"), registry.channelsSnapshot().size());
        } else {
            registry.registerChannel(reg.channelId(), reg.host(), reg.port(), conn);
            intercoord.registerChannel(reg.channelId(), reg.host(), reg.port(), reg.onlineCount());
        }
    }

    private void handleHeartbeat(InternalConnection conn, InternalFrame frame) {
        InternalProtocol.HeartbeatPayload hb = JsonCodec.decode(frame.payloadText(),
                InternalProtocol.HeartbeatPayload.class.getName());
        if (hb == null) {
            return;
        }
        intercoord.heartbeatChannel(hb.channelId(), hb.onlineCount());
    }

    private void handleEvent(InternalConnection conn, InternalFrame frame) {
        InternalProtocol.EventPayload event = JsonCodec.decode(frame.payloadText(),
                InternalProtocol.EventPayload.class.getName());
        if (event == null) {
            log.warn(I18n.message("log.coordinator.event_parse_failed"));
            return;
        }
        // 先本地派发（管理进程 = coordinator 进程，架构 4.6.2：OnlinePlayerMirror 等订阅者在此）。
        // 频道进程经 RemoteEventBus 已本地派发过自身订阅者（ChannelLocationBinder），coordinator
        // 本地派发的是管理进程订阅者——两进程各派发自己的，不冲突。
        Object payload = JsonCodec.decode(event.payload(), event.type());
        if (payload != null) {
            localEventBus.send(event.target(), payload);
        }
        // 再按 target 路由到远端频道进程（管理进程已本地派发，不再远端转发，防镜像重复接收）
        routeEvent(event);
    }

    private void routeEvent(InternalProtocol.EventPayload event) {
        String target = event.target();
        // target = channel:{id} → 定向转发该频道
        if (target != null && target.startsWith("channel:")) {
            String idStr = target.substring("channel:".length());
            try {
                int channelId = Integer.parseInt(idStr);
                InternalConnection conn = registry.channel(channelId);
                if (conn != null && conn.isActive()) {
                    forwardEvent(conn, event);
                } else {
                    log.debug(I18n.message("log.coordinator.event_channel_unconnected"), target);
                }
            } catch (NumberFormatException e) {
                log.warn(I18n.message("log.coordinator.event_target_invalid"), target);
            }
            return;
        }
        // target = * → 群发所有远端频道（管理进程已本地派发）
        if (MessageTargets.BROADCAST.equals(target)) {
            Map<Integer, InternalConnection> all = registry.channelsSnapshot();
            all.values().forEach(conn -> forwardEvent(conn, event));
            return;
        }
        // 其它 target（如 online-player-events）→ 管理进程已本地派发（架构 4.6.2 管理进程=coordinator）。
        // 若未来管理进程独立部署，此处补转发管理进程连接（当前不转发防镜像重复）。
        log.debug(I18n.message("log.coordinator.event_local_only"), target);
    }

    private void forwardEvent(InternalConnection conn, InternalProtocol.EventPayload event) {
        conn.send(new DefaultInternalFrame(InternalFrame.MessageType.EVENT,
                conn.nextMessageId(), JsonCodec.encode(event)));
    }
}
