package org.gms.net.netty.internal;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;

/**
 * 内部通信连接（架构 4.5：每个进程 Netty 客户端 + 服务端，与 coordinator TCP 长连接）。
 *
 * <p>包装一条已接入 pipeline（已含 {@link InternalFrameDecoder}/{@link InternalFrameEncoder}）
 * 的 channel，提供：
 * <ul>
 *   <li>{@link #send}：单向投递（EVENT/HEARTBEAT/REGISTER）。</li>
 *   <li>{@link #request}：请求-响应（RPC：发帧 + 同 messageId 的 RPC_RESPONSE 到来时完成
 *       {@link CompletableFuture}，用于频道侧 IntercoordService 桩）。</li>
 *   <li>帧分发：RPC_RESPONSE 按 messageId 匹配 pending；其余经 {@link #onFrame} 交给业务。</li>
 *   <li>断链：pending future 全部失败 + {@code closeHandler} 回调（上层触发重连）。</li>
 * </ul>
 *
 * <p>线程安全：可多线程并发发送/请求；帧分发在 Netty IO 线程（订阅者须自行移交，EventBus 契约同）。
 */
@Log4j2
public final class InternalConnection extends ChannelInboundHandlerAdapter implements AutoCloseable {



    private final Channel channel;
    private final Runnable closeHandler;
    private final AtomicLong messageIdSeq = new AtomicLong();
    private final ConcurrentMap<Long, CompletableFuture<InternalFrame>> pending = new ConcurrentHashMap<>();

    private volatile Consumer<InternalFrame> frameHandler;
    private volatile Consumer<RpcRequestEnvelope> rpcRequestHandler;

    private InternalConnection(Channel channel, Runnable closeHandler) {
        this.channel = channel;
        this.closeHandler = closeHandler;
    }

    /**
     * 附加到已配置 pipeline 的 channel（调用方须已放入 decoder/encoder）。返回连接句柄，
     * 业务随后用 {@link #onFrame} 注册帧处理。
     */
    public static InternalConnection attach(Channel channel, Runnable closeHandler) {
        InternalConnection conn = new InternalConnection(channel, closeHandler);
        channel.pipeline().addLast("internal-connection", conn);
        return conn;
    }

    /** 注册帧处理（EVENT/HEARTBEAT/REGISTER；RPC_RESPONSE 已被内部匹配）。 */
    public void onFrame(Consumer<InternalFrame> handler) {
        this.frameHandler = handler;
    }

    /** 注册 RPC 请求处理（对端发来的 RPC，如管理进程→频道运维操作）。返回响应经 {@link #replyRpc}。 */
    public void onRpcRequest(Consumer<RpcRequestEnvelope> handler) {
        this.rpcRequestHandler = handler;
    }

    /** RPC 请求 + 帧 messageId（响应关联用）。 */
    public record RpcRequestEnvelope(InternalProtocol.RpcRequest request, long messageId) {
    }

    /** 回复一次 RPC 请求（同 messageId 的 RPC_RESPONSE 帧）。 */
    public void replyRpc(long requestMessageId, InternalProtocol.RpcResponse response) {
        send(new DefaultInternalFrame(InternalFrame.MessageType.RPC_RESPONSE,
                requestMessageId, JsonCodec.encode(response)));
    }

    /** 下一帧消息 ID（RPC 请求唯一标识，响应用同 ID 匹配）。 */
    public long nextMessageId() {
        return messageIdSeq.incrementAndGet();
    }

    /** 单向发送一帧（投递/心跳/注册）。 */
    public void send(InternalFrame frame) {
        channel.writeAndFlush(frame);
    }

    /** 请求-响应：发送帧，同 messageId 的 RPC_RESPONSE 到来时完成 future（超时由调用方控制）。 */
    public CompletableFuture<InternalFrame> request(InternalFrame frame) {
        CompletableFuture<InternalFrame> fut = new CompletableFuture<>();
        pending.put(frame.messageId(), fut);
        try {
            channel.writeAndFlush(frame);
        } catch (RuntimeException e) {
            pending.remove(frame.messageId());
            fut.completeExceptionally(e);
        }
        return fut;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof InternalFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (frame.type() == InternalFrame.MessageType.RPC_RESPONSE) {
            CompletableFuture<InternalFrame> fut = pending.remove(frame.messageId());
            if (fut != null) {
                fut.complete(frame);
                return;
            }
            // 未匹配 pending（如 coordinator 收到频道回的管理进程 RPC 响应）→ 回落 frameHandler
            // 由 CoordinatorFrameRouter.handleRpcResponse 回传管理进程。
            Consumer<InternalFrame> respHandler = frameHandler;
            if (respHandler != null) {
                try {
                    respHandler.accept(frame);
                } catch (RuntimeException e) {
                    log.error(I18n.message("log.internal.rpc_response_error"), frame.messageId(), e);
                }
                return;
            }
            log.warn(I18n.message("log.internal.unmatched_rpc_response"), frame.messageId());
            return;
        }
        if (frame.type() == InternalFrame.MessageType.RPC) {
            // RPC 优先走专用 handler（频道进程处理管理进程运维 RPC）；
            // 未设置则回落 frameHandler（coordinator 端由 CoordinatorFrameRouter 统一处理）。
            Consumer<RpcRequestEnvelope> rpcHandler = rpcRequestHandler;
            if (rpcHandler != null) {
                InternalProtocol.RpcRequest req = JsonCodec.decode(frame.payloadText(),
                        InternalProtocol.RpcRequest.class.getName());
                if (req != null) {
                    try {
                        rpcHandler.accept(new RpcRequestEnvelope(req, frame.messageId()));
                    } catch (RuntimeException e) {
                        log.error(I18n.message("log.internal.rpc_process_error"), req.method(), e);
                    }
                }
                return;
            }
            // 回落 frameHandler（coordinator 端路由）
        }
        Consumer<InternalFrame> handler = frameHandler;
        if (handler == null) {
            log.warn(I18n.message("log.internal.no_frame_handler"), frame.type(), frame.messageId());
            return;
        }
        try {
            handler.accept(frame);
        } catch (RuntimeException e) {
            // 日志红线 9：log.error("描述", e)，禁用 printStackTrace
            log.error(I18n.message("log.internal.frame_process_error"), frame.type(), frame.messageId(), e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 断链：pending RPC 全部失败（防调用方永久等待）
        RuntimeException closed = new IllegalStateException(I18n.message("error.internal.connection_closed"));
        pending.forEach((id, fut) -> fut.completeExceptionally(closed));
        pending.clear();
        if (closeHandler != null) {
            closeHandler.run();
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void close() {
        channel.close();
    }
}
