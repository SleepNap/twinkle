package org.gms.net.netty.internal;

import org.gms.coordinator.ChannelRegistry;
import org.gms.coordinator.CoordinatorService;
import org.gms.coordinator.LocationTable;
import org.gms.coordinator.SingleOwnerStore;
import org.gms.event.InProcessEventBus;
import org.gms.message.MessageTargets;
import org.gms.message.WhisperRequest;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * coordinator ↔ 频道 内部通信端到端（架构 4.5：REGISTER 上报 + EVENT 路由 + IntercoordService RPC）。
 *
 * <p>模拟单机多进程形态：coordinator 端（InternalServer + CoordinatorFrameRouter + 进程内真值）
 * 与频道端（InternalClient + RemoteEventBus + RemoteIntercoordService）各一套，走 loopback TCP。
 */
class CoordinatorChannelIntegrationTest {

    @Test
    void 频道注册上报RPC定位与EVENT路由() throws Exception {
        // ---- coordinator 端 ----
        IntercoordService truth = new CoordinatorService(new LocationTable(), new ChannelRegistry(), new SingleOwnerStore());
        InProcessEventBus coordinatorBus = new InProcessEventBus();
        ChannelConnectionRegistry registry = new ChannelConnectionRegistry();
        CoordinatorFrameRouter router = new CoordinatorFrameRouter(registry, truth, coordinatorBus);

        InternalServer server = new InternalServer(router.connectionHandler());
        server.start(0);

        // ---- 频道端（channel=5） ----
        AtomicReference<InternalConnection> connRef = new AtomicReference<>();
        AtomicReference<WhisperRequest> delivered = new AtomicReference<>();
        InProcessEventBus channelBus = new InProcessEventBus();
        // 模拟频道进程订阅本频道 target（ChannelMessageSubscriber 行为）
        channelBus.subscribe(MessageTargets.channel(5), WhisperRequest.class, delivered::set);

        CoordinatorLink link = new CoordinatorLink(new InetSocketAddress("127.0.0.1", server.boundPort()), 50);
        link.addConnectListener(conn -> {
            connRef.set(conn);
            // 频道进程启动上报身份（ChannelRegistryRegistrar 行为）
            conn.send(new DefaultInternalFrame(InternalFrame.MessageType.REGISTER,
                    conn.nextMessageId(), JsonCodec.encode(
                    new InternalProtocol.RegisterPayload(5, "127.0.0.1", 8584, false, 0))));
        });
        link.start();

        try {
            // 等待注册完成
            await(() -> truth.channel(5).isPresent());

            // ---- RPC 定位 ----
            RemoteIntercoordService remote = new RemoteIntercoordService(link);
            remote.registerPlayer(1001, 5);
            assertThat(remote.locate(1001)).contains(5);

            // ---- EVENT 路由：频道 A 发悄悄话 → coordinator 路由回本频道 ----
            RemoteEventBus remoteBus = new RemoteEventBus(channelBus, link);
            remoteBus.send(MessageTargets.channel(5), new WhisperRequest(1001, "Alice", 1002, "Bob", "你好"));

            await(() -> delivered.get() != null);
            assertThat(delivered.get().fromId()).isEqualTo(1001);
            assertThat(delivered.get().content()).isEqualTo("你好");

            // ---- 管理进程 channels() 快照（RPC 广播给 coordinator） ----
            assertThat(remote.channels().get(5).channelId()).isEqualTo(5);
            assertThat(remote.channels().get(5).host()).isEqualTo("127.0.0.1");
            assertThat(remote.channels().get(5).port()).isEqualTo(8584);
        } finally {
            link.close();
            server.close();
        }
    }

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
}
