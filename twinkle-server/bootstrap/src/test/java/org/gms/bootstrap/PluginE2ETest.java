package org.gms.bootstrap;

import org.gms.event.InProcessEventBus;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.LogicSystemRegistry;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.PacketHandler;
import org.gms.plugin.ContributionHandle;
import org.gms.plugin.runtime.ContributionRouter;
import org.gms.plugin.runtime.PluginManager;
import org.gms.tick.GameTickLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件宿主接线端到端（架构 7.1：插件可装卸 + 贡献点落注册表）。
 *
 * <p>手动装配（仿 GamePlayE2ETest）：真实 TwinklePluginHost + PluginManager + 测试插件 jar，
 * 验证四类贡献点：
 * <ul>
 *   <li>packet-handler → HandlerRegistry（新 opcode 可收包）</li>
 *   <li>tick-handler → TickScheduler（每 tick 计数）</li>
 *   <li>event-listener → EventBus（事件被插件监听）</li>
 *   <li>logic-system → LogicSystemRegistry（可查）</li>
 * </ul>
 * 再 unload → 断言全部贡献点回滚（opcode 移除、tick 停止、事件退订、逻辑系统移除）。
 *
 * <p>插件代码跨 classloader 后无法直接读其静态字段，因此插件贡献类把计数写到
 * {@code tmp/counters.txt}（路径在生成源码时烘焙进去），测试读文件断言。
 */
class PluginE2ETest {

    @TempDir
    Path tmp;

    private static final String MANIFEST = """
            plugin.id=com.acme.demo
            plugin.name=Demo
            plugin.version=1.0.0
            plugin.scope=channel
            plugin.sdk-version=1
            plugin.main-class=com.acme.demo.DemoPlugin

            contribution.0.type=packet-handler
            contribution.0.opcode=GENERAL_CHAT
            contribution.0.class=com.acme.demo.DemoChatHandler
            contribution.0.version=1

            contribution.1.type=tick-handler
            contribution.1.class=com.acme.demo.DemoTickHandler
            contribution.1.version=1

            contribution.2.type=event-listener
            contribution.2.target=online-player-events
            contribution.2.event-class=org.gms.service.admin.OnlinePlayerEvents$PlayerOnline
            contribution.2.class=com.acme.demo.DemoEventListener
            contribution.2.version=1

            contribution.3.type=logic-system
            contribution.3.key=combat
            contribution.3.class=com.acme.demo.DemoCombatSystem
            contribution.3.version=1
            """;

    @Test
    void pluginLoadAndUnloadContributions() throws Exception {
        Path counterFile = tmp.resolve("counters.txt");
        buildDemoPlugin(counterFile);

        // ---- 组装宿主（真实 TwinklePluginHost + 注册表） ----
        HandlerRegistry registry = new HandlerRegistry();
        LogicSystemRegistry logicSystems = new LogicSystemRegistry();
        GameTickLoop tickLoop = new GameTickLoop(5);
        VersionGate versionGate = new DefaultVersionGate();
        EntityReloadCoordinator reloadCoordinator = new EntityReloadCoordinator();
        InProcessEventBus eventBus = new InProcessEventBus();

        org.gms.bootstrap.plugin.TwinklePluginHost host = new org.gms.bootstrap.plugin.TwinklePluginHost(
                registry, logicSystems, tickLoop, eventBus, versionGate, reloadCoordinator);

        ContributionRouter router = new ContributionRouter() {
            @Override
            public <T> ContributionHandle register(String contributionType, T contribution, int version) {
                return host.registerCommand(contributionType, contribution, version);
            }

            @Override
            public <T> ContributionHandle subscribe(String target, Class<T> eventType, java.util.function.Consumer<T> consumer) {
                return host.subscribeCommand(target, eventType, consumer);
            }
        };
        Function<Class<?>, Object> serviceResolver = type -> null;
        PluginManager manager = new PluginManager(tmp, host,
                PluginE2ETest.class.getClassLoader(), serviceResolver, router);

        try {
            var descriptors = manager.scan();
            assertThat(descriptors).hasSize(1);

            manager.load(descriptors.get(0));

            // ---- 断言贡献点已注册 ----
            assertThat(registry.find(RecvOpcode.GENERAL_CHAT.getValue())).isPresent();
            assertThat(logicSystems.find("combat")).isPresent();
            assertThat(tickLoop.handlerCount()).isEqualTo(1);

            // 事件监听：发 PlayerOnline → 插件写计数
            eventBus.send("online-player-events",
                    new org.gms.service.admin.OnlinePlayerEvents.PlayerOnline(1L, "Hero", 0, 1, 0));
            awaitCounter(counterFile, "events", 1);

            // 收包：GENERAL_CHAT 经插件 handler 处理
            PacketHandler pluginHandler = registry.find(RecvOpcode.GENERAL_CHAT.getValue()).get();
            pluginHandler.handle(null, null);
            awaitCounter(counterFile, "packets", 1);

            // tick：启动循环，等至少 1 tick，插件 tick 计数 ≥1
            tickLoop.start();
            awaitCounterAtLeast(counterFile, "ticks", 1);

            // ---- 卸载 → 全部贡献点回滚 ----
            manager.unload("com.acme.demo");
            assertThat(registry.find(RecvOpcode.GENERAL_CHAT.getValue())).isEmpty();
            assertThat(logicSystems.find("combat")).isEmpty();
            assertThat(tickLoop.handlerCount()).isZero();

            // 事件退订：再发事件不再触发（计数保持不变）
            int before = readCounter(counterFile, "events");
            eventBus.send("online-player-events",
                    new org.gms.service.admin.OnlinePlayerEvents.PlayerOnline(2L, "Hero2", 0, 1, 0));
            assertThat(readCounter(counterFile, "events")).isEqualTo(before);
        } finally {
            manager.close();
            tickLoop.stop();
        }
    }

    // ---------- 插件 jar 构建（源码烘焙计数文件路径） ----------

    private void buildDemoPlugin(Path counterFile) throws Exception {
        String counterPath = counterFile.toString().replace("\\", "/");
        String writeTemplate = """
                    try {
                        java.nio.file.Files.writeString(
                            java.nio.file.Path.of("%1$s"),
                            "%2$s;1;\\n",
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (java.io.IOException ignored) { }
                """;

        String packetHandler = """
                package com.acme.demo;
                public class DemoChatHandler implements org.gms.net.packet.PacketHandler {
                    public void handle(org.gms.net.packet.PacketSession session, org.gms.net.packet.InPacket packet) {
                """ + writeTemplate.formatted(counterPath, "packets") + """
                }
                }
                """;
        String tickHandler = """
                package com.acme.demo;
                public class DemoTickHandler implements org.gms.tick.TickHandler {
                    public void tick(long tickCount) {
                """ + writeTemplate.formatted(counterPath, "ticks") + """
                }
                }
                """;
        String eventListener = """
                package com.acme.demo;
                public class DemoEventListener {
                    public void onEvent(org.gms.service.admin.OnlinePlayerEvents.PlayerOnline event) {
                """ + writeTemplate.formatted(counterPath, "events") + """
                }
                }
                """;
        String logicSystem = """
                package com.acme.demo;
                public class DemoCombatSystem { }
                """;
        String mainClass = """
                package com.acme.demo;
                import org.gms.plugin.Plugin;
                import org.gms.plugin.PluginContext;
                public class DemoPlugin implements Plugin {
                    public void start(PluginContext ctx) throws Exception { }
                }
                """;
        writePluginJar(tmp.resolve("com.acme.demo.jar"), MANIFEST, Map.of(
                "com.acme.demo.DemoChatHandler", packetHandler,
                "com.acme.demo.DemoTickHandler", tickHandler,
                "com.acme.demo.DemoEventListener", eventListener,
                "com.acme.demo.DemoCombatSystem", logicSystem,
                "com.acme.demo.DemoPlugin", mainClass));
    }

    private static void writePluginJar(Path jar, String manifest, Map<String, String> classSources) throws Exception {
        Path classesDir = Files.createTempDirectory("plugin-src");
        for (var e : classSources.entrySet()) {
            Path f = classesDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        Path out = Files.createTempDirectory("plugin-cls");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        String cp = System.getProperty("java.class.path");
        java.util.List<String> sources;
        try (var stream = Files.walk(classesDir)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString).toList();
        }
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int rc = compiler.run(null, null, err,
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of("-encoding", "UTF-8", "-d", out.toString(), "-cp", cp),
                                sources.stream())
                        .toArray(String[]::new));
        if (rc != 0) {
            throw new IllegalStateException("插件源码编译失败:\n" + err);
        }
        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new java.util.jar.JarEntry("META-INF/twinkle-plugin.properties"));
            jos.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
            try (var stream = Files.walk(out)) {
                for (Path f : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                    jos.putNextEntry(new java.util.jar.JarEntry(out.relativize(f).toString().replace('\\', '/')));
                    jos.write(Files.readAllBytes(f));
                    jos.closeEntry();
                }
            }
        }
    }

    // ---------- 计数文件读写 ----------

    private static int readCounter(Path file, String key) throws Exception {
        if (!Files.exists(file)) {
            return 0;
        }
        int count = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith(key + ";")) {
                count++;
            }
        }
        return count;
    }

    private static void awaitCounter(Path file, String key, int expected) throws Exception {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(readCounter(file, key)).isEqualTo(expected));
    }

    private static void awaitCounterAtLeast(Path file, String key, int minimum) throws Exception {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(readCounter(file, key)).isGreaterThanOrEqualTo(minimum));
    }
}
