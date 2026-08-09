package org.gms.net.netty.internal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.event.EventBus;
import org.gms.event.InProcessEventBus;
import org.gms.event.ReliableDelivery;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 网络事件总线（架构 4.5：同一 {@link EventBus} 接口的网络实现，配置切换进程内 ↔ 网络）。
 *
 * <p>装饰进程内 {@link InProcessEventBus}（本地订阅/派发复用），叠加跨进程转发：
 * <ul>
 *   <li><b>发送</b>：{@code send(target, payload)} 先本地派发（本进程订阅者，如
 *       ChannelMessageSubscriber / ChannelLocationBinder），再包装成 EVENT 帧经
 *       {@link CoordinatorLink} 转发 coordinator 路由。</li>
 *   <li><b>接收</b>：coordinator 路由来的 EVENT 帧（目标本进程）→ 解码 → 本地派发。</li>
 * </ul>
 *
 * <p>路由去重（架构 4.5）：频道进程只订阅本频道 target（{@code channel:{id}}），coordinator
 * 按 target 定向转发——目标频道进程的本地订阅恰好命中，不会重复。广播（{@code *}）由 coordinator
 * 群发所有频道。
 *
 * <p>断链降级：coordinator 失联时发送仅本地派发（单频道可玩，架构 4.5 故障矩阵），
 * 重连后自动恢复转发（CoordinatorLink 通知重挂帧处理器）。
 *
 * <p>可靠投递：实现 {@link ReliableDelivery}——{@link ReliableEventBus} 发送时携带序号，
 * 转发帧带 {@code streamId/seq/messageId}，接收侧目标进程可经 {@link ReliableReceiver}
 * 做恰好一次判定（bus_stream 持久化去重）。
 */
public final class RemoteEventBus implements EventBus, ReliableDelivery {

    private static final Logger LOG = LogManager.getLogger(RemoteEventBus.class);

    private final InProcessEventBus local;
    private final CoordinatorLink link;

    public RemoteEventBus(InProcessEventBus local, CoordinatorLink link) {
        this.local = local;
        this.link = link;
        // 连接建立（含重连）→ 重挂帧处理器（接收 coordinator 路由来的 EVENT）
        link.addConnectListener(conn -> conn.onFrame(frame -> {
            if (frame.type() == InternalFrame.MessageType.EVENT) {
                dispatchRemote(frame);
            }
        }));
    }

    @Override
    public <T> CompletableFuture<Void> send(String target, T payload) {
        return forward(target, payload, null, null, null);
    }

    @Override
    public <T> void sendReliable(String streamId, long seq, String messageId, String target, T payload) {
        forward(target, payload, streamId, seq, messageId);
    }

    private <T> CompletableFuture<Void> forward(String target, T payload,
                                                String streamId, Long seq, String messageId) {
        // 1) 本地派发（本进程订阅者）
        local.send(target, payload);
        // 2) 跨进程转发 coordinator（低频控制消息，架构 4.5）；未连接仅本地派发（单频道可玩）
        InternalConnection conn = link.connection();
        if (conn == null) {
            LOG.debug("coordinator 未连接，事件仅本地派发: target={}", target);
            return CompletableFuture.completedFuture(null);
        }
        InternalProtocol.EventPayload event = new InternalProtocol.EventPayload(
                target, JsonCodec.typeName(payload), JsonCodec.encode(payload), streamId, seq, messageId);
        DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.EVENT,
                conn.nextMessageId(), JsonCodec.encode(event));
        conn.send(frame);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler) {
        return local.subscribe(target, type, handler);
    }

    private void dispatchRemote(InternalFrame frame) {
        InternalProtocol.EventPayload event = JsonCodec.decode(frame.payloadText(), InternalProtocol.EventPayload.class.getName());
        if (event == null) {
            LOG.warn("EVENT 帧负载解析失败: messageId={}", frame.messageId());
            return;
        }
        Object payload = JsonCodec.decode(event.payload(), event.type());
        if (payload == null) {
            LOG.warn("EVENT 负载反序列化失败: type={}", event.type());
            return;
        }
        // 可靠序号存在 → 携带派发（订阅方若用 ReliableReceiver 可恰好一次）；
        // 否则普通本地派发
        if (event.streamId() != null) {
            local.sendReliable(event.streamId(), event.seq() == null ? 0 : event.seq(),
                    event.messageId(), event.target(), payload);
        } else {
            local.send(event.target(), payload);
        }
    }
}
