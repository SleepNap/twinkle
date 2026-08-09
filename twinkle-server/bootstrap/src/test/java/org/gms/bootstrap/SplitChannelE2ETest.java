package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import org.gms.channel.ChannelMessageSubscriber;
import org.gms.event.EventBus;
import org.gms.event.InProcessEventBus;
import org.gms.message.MessageTargets;
import org.gms.message.WhisperRequest;
import org.gms.net.packet.PacketSession;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 阶段 A 验收：单机多进程 split 档端到端（架构 4.5 分布式特例）。
 *
 * <p>同 JVM 起 3 个独立 ApplicationContext（coordinator + 2 个 channel，loopback TCP），
 * 验证：
 * <ol>
 *   <li>频道进程启动 → 上报 coordinator 注册中心（channel 注册表可见）。</li>
 *   <li>频道 A 发跨频道悄悄话 → coordinator 路由 → 频道 B 收到（消息总线）。</li>
 *   <li>频道 A 进图玩家 → 定位表登记 → 频道 B 能 locate（IntercoordService RPC）。</li>
 * </ol>
 *
 * <p>三个 context 共享临时 SQLite（各自连接），channel id/端口区分；WZ/脚本用临时空目录
 * （ChannelConfig 的 WZ/脚本读不到目录不阻断启动，架构 6.4 注释）。
 */
class SplitChannelE2ETest {

    @Test
    void splitChannelCrossProcessMessaging() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-split-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-split-script").toString();

        // coordinator（管理进程）：内部通信监听 8510，HTTP 随机端口
        Map<String, Object> coordinatorProps = Map.of(
                "twinkle.profile", "split-channel",
                "twinkle.role", "coordinator",
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.coordinator.port", "8510",
                "twinkle.admin.restart.exit", "false",
                "twinkle.script.path", scriptDir);

        // 频道 1：内部连 coordinator 8510，频道端口 8584
        Map<String, Object> channel1Props = Map.of(
                "twinkle.profile", "split-channel",
                "twinkle.role", "channel",
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.id", "1",
                "twinkle.net.channel.port", "8584",
                "twinkle.coordinator.port", "8510",
                "twinkle.script.path", scriptDir);

        // 频道 2：内部连 coordinator 8510，频道端口 8585
        Map<String, Object> channel2Props = Map.of(
                "twinkle.profile", "split-channel",
                "twinkle.role", "channel",
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.id", "2",
                "twinkle.net.channel.port", "8585",
                "twinkle.coordinator.port", "8510",
                "twinkle.script.path", scriptDir);

        try (ApplicationContext coordinator = ApplicationContext.run(coordinatorProps);
             ApplicationContext channel1 = ApplicationContext.run(channel1Props);
             ApplicationContext channel2 = ApplicationContext.run(channel2Props)) {

            // ---- 1) 频道注册表：coordinator 看到两个频道 ----
            IntercoordService coordTruth = coordinator.getBean(IntercoordService.class);
            await(() -> coordTruth.channels().size() == 2);
            assertThat(coordTruth.channels().keySet()).containsExactlyInAnyOrder(1, 2);

            // ---- 2) 频道 2 的 ChannelMessageSubscriber 订阅本频道 target ----
            // 频道 2 的 EventBus = RemoteEventBus（@Primary 覆盖 InProcessEventBus）
            AtomicReference<WhisperRequest> received = new AtomicReference<>();
            InProcessEventBus localBus = channel2.getBean(InProcessEventBus.class);
            localBus.subscribe(MessageTargets.channel(2), WhisperRequest.class, received::set);

            // ---- 3) 频道 1 发跨频道悄悄话 → coordinator 路由 → 频道 2 本地派发 ----
            EventBus channel1Bus = channel1.getBean(EventBus.class);
            channel1Bus.send(MessageTargets.channel(2), new WhisperRequest(1001, "Alice", 2002, "Bob", "跨频道你好"));

            await(() -> received.get() != null);
            assertThat(received.get().content()).isEqualTo("跨频道你好");
            assertThat(received.get().toId()).isEqualTo(2002);

            // ---- 4) 频道 1 进图玩家 → 定位表（RPC 到 coordinator 真值） ----
            IntercoordService channel1Coord = channel1.getBean(IntercoordService.class);
            channel1Coord.registerPlayer(3001, 1);
            await(() -> coordTruth.locate(3001).isPresent());
            assertThat(coordTruth.locate(3001)).contains(1);
        }
    }

    private interface BoolSupplier {
        boolean get() throws Exception;
    }

    private static void await(BoolSupplier cond) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (cond.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待超时");
    }
}
