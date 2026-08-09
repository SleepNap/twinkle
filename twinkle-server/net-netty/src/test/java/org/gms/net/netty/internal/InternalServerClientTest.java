package org.gms.net.netty.internal;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部通信客户端/服务端集成（架构 4.5：TCP 长连接、帧收发、RPC 响应匹配、断链重连）。
 */
class InternalServerClientTest {

    @Test
    void 长连接帧收发与RPC响应匹配() throws Exception {
        AtomicReference<InternalConnection> serverConn = new AtomicReference<>();
        AtomicReference<InternalFrame> receivedEvent = new AtomicReference<>();

        InternalServer server = new InternalServer(conn -> {
            serverConn.set(conn);
            conn.onFrame(frame -> {
                if (frame.type() == InternalFrame.MessageType.EVENT) {
                    receivedEvent.set(frame);
                } else if (frame.type() == InternalFrame.MessageType.RPC) {
                    // RPC 请求 → 回 RPC_RESPONSE（同 messageId）
                    conn.send(new DefaultInternalFrame(InternalFrame.MessageType.RPC_RESPONSE,
                            frame.messageId(), "pong:" + new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8)));
                }
            });
        });
        server.start(0);

        InternalClient client = new InternalClient(new InetSocketAddress("127.0.0.1", server.boundPort()), conn -> {
            // 连接建立：发一个 EVENT 帧验证单向
            conn.send(new DefaultInternalFrame(InternalFrame.MessageType.EVENT, conn.nextMessageId(), "hello"));
        }, 50);
        client.connect();

        try {
            await(() -> receivedEvent.get() != null);
            assertThat(receivedEvent.get().type()).isEqualTo(InternalFrame.MessageType.EVENT);
            assertThat(new String(receivedEvent.get().payload(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("hello");

            // 客户端发 RPC → 等响应
            InternalConnection cc = awaitConn(() -> client.connection());
            long reqId = cc.nextMessageId();
            CompletableFuture<InternalFrame> resp = cc.request(
                    new DefaultInternalFrame(InternalFrame.MessageType.RPC, reqId, "ping"));
            InternalFrame reply = resp.get(3, TimeUnit.SECONDS);
            assertThat(reply.type()).isEqualTo(InternalFrame.MessageType.RPC_RESPONSE);
            assertThat(reply.messageId()).isEqualTo(reqId);
            assertThat(new String(reply.payload(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("pong:ping");
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void 断链重连() throws Exception {
        AtomicInteger connects = new AtomicInteger();

        InternalServer server = new InternalServer(conn -> connects.incrementAndGet());
        server.start(0);

        InternalClient client = new InternalClient(new InetSocketAddress("127.0.0.1", server.boundPort()),
                conn -> connects.incrementAndGet(), 50);
        client.connect();

        try {
            await(() -> connects.get() >= 1);
            int before = connects.get();

            // 主动关闭服务端连接 → 客户端应重连（连接回调次数增加）
            InternalConnection sc = awaitConn(() -> client.connection());
            sc.close();
            await(() -> connects.get() > before);
            assertThat(connects.get()).isGreaterThan(before);
        } finally {
            client.close();
            server.close();
        }
    }

    // ---- 测试工具 ----

    private interface BoolSupplier {
        boolean get() throws Exception;
    }

    private static void await(BoolSupplier cond) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (cond.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待超时");
    }

    private static InternalConnection awaitConn(java.util.function.Supplier<InternalConnection> supplier)
            throws Exception {
        final InternalConnection[] ref = new InternalConnection[1];
        await(() -> {
            ref[0] = supplier.get();
            return ref[0] != null;
        });
        return ref[0];
    }
}
