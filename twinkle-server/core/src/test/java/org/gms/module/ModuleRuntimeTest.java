package org.gms.module;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.JarFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.gms.concurrent.GameExecution;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 使用真实编译产物换代，覆盖操作固定版本、失败隔离和旧代释放。 */
public class ModuleRuntimeTest {
    public interface Probe { public int value(); }
    public interface Lifecycle { public void closed(); }
    @TempDir public Path directory;

    @Test public void repeatedJarReplacementChangesCodeAndReleasesOldGenerations() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        try (var owner = new GameExecution("test-owner", new DefaultVersionGate());
             var runtime = runtime(jar(1), closed)) {
            runtime.bind(owner, () -> { });
            Probe proxy = runtime.service(Probe.class);
            assertThat(owner.call(proxy::value)).isEqualTo(1);
            long originalVersion = owner.version();
            for (int version = 2; version <= 6; version++) {
                assertThat(runtime.reload(jar(version)).targets()).containsOnlyKeys("default", "test-owner")
                        .containsValue("APPLIED");
                assertThat(owner.call(proxy::value)).isEqualTo(version);
                assertThat(runtime.liveGenerations()).isEqualTo(2);
            }
            assertThat(owner.continueAt(originalVersion, () -> fail("旧回调不应运行")).join()).isFalse();
            assertThat(closed).hasValue(10);
        }
        assertThat(closed).hasValue(12);
    }

    @Test public void wholeActorOperationKeepsOldCodeUntilItFinishes() throws Exception {
        try (var owner = new GameExecution("test-owner", new DefaultVersionGate());
             var runtime = runtime(jar(10), new AtomicInteger());
             var background = Executors.newVirtualThreadPerTaskExecutor()) {
            runtime.bind(owner, () -> { });
            Probe proxy = runtime.service(Probe.class);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var inFlight = owner.submit(() -> {
                int first = proxy.value(); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("等待超时"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return List.of(first, proxy.value());
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Path next = jar(20);
            var reload = background.submit(() -> runtime.reload(next));
            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).containsExactly(10, 10);
            assertThat(reload.get(5, TimeUnit.SECONDS).targets().get("test-owner")).isEqualTo("APPLIED");
            assertThat(owner.call(proxy::value)).isEqualTo(20);
        }
    }

    @Test public void badCandidateKeepsEveryTargetAndFailedSafePointKeepsOnlyThatTarget() throws Exception {
        try (var first = new GameExecution("first", new DefaultVersionGate());
             var second = new GameExecution("second", new DefaultVersionGate());
             var runtime = runtime(jar(1), new AtomicInteger())) {
            runtime.bind(first, () -> { throw new IllegalStateException("未完成操作"); });
            runtime.bind(second, () -> { });
            Probe proxy = runtime.service(Probe.class);
            Path broken = directory.resolve("broken.jar"); Files.writeString(broken, "损坏内容");
            assertThatThrownBy(() -> runtime.reload(broken)).isInstanceOf(Exception.class);
            assertThat(first.call(proxy::value)).isEqualTo(1);
            assertThat(second.call(proxy::value)).isEqualTo(1);
            var result = runtime.reload(jar(2));
            assertThat(result.targets()).containsEntry("first", "FAILED").containsEntry("second", "APPLIED");
            assertThat(first.call(proxy::value)).isEqualTo(1);
            assertThat(second.call(proxy::value)).isEqualTo(2);
            assertThat(runtime.liveGenerations()).isEqualTo(3);
            assertThatThrownBy(() -> runtime.reload(jar(3))).isInstanceOf(IllegalStateException.class);
            assertThat(second.call(proxy::value)).isEqualTo(2);
        }
    }

    @Test public void pendingCleanupBlocksAnotherVersionAndSameArtifactDoesNotAdvanceAgain() throws Exception {
        var disposal = new java.util.ArrayList<Runnable>();
        Path first = jar(1), second = jar(2), third = jar(3);
        var runtime = new ModuleRuntime("test-logic", "org.gms.logic.test.", List.of(Probe.class),
                new HostServices(Map.of(Lifecycle.class, (Lifecycle) () -> { })), first,
                directory.resolve("cache"), disposal::add);
        try {
            runtime.reload(second);
            assertThat(runtime.service(Probe.class).value()).isEqualTo(2);
            assertThatThrownBy(() -> runtime.reload(third)).isInstanceOf(IllegalStateException.class);
            disposal.removeFirst().run();
            runtime.reload(second);
            assertThat(disposal).isEmpty();
            runtime.reload(third);
            assertThat(runtime.service(Probe.class).value()).isEqualTo(3);
        } finally { runtime.close(); disposal.forEach(Runnable::run); }
    }

    @Test public void restartUsesConfirmedArtifactAndRejectedCandidateDoesNotPoisonStartup() throws Exception {
        Files.copy(jar(1), directory.resolve("test-logic.jar"));
        Files.createDirectories(directory.resolve("incoming"));
        Path incoming = directory.resolve("incoming/test-logic.jar");
        Files.copy(jar(2), incoming);
        HostServices host = new HostServices(Map.of(Lifecycle.class, (Lifecycle) () -> { }));
        try (var registry = new ModuleRegistry(directory, Runnable::run)) {
            var runtime = registry.register("test-logic", "org.gms.logic.test.", List.of(Probe.class), host);
            registry.reload("test-logic");
            assertThat(runtime.service(Probe.class).value()).isEqualTo(2);
            Files.writeString(incoming, "损坏候选");
            assertThatThrownBy(() -> registry.reload("test-logic")).isInstanceOf(Exception.class);
        }
        try (var registry = new ModuleRegistry(directory, Runnable::run)) {
            var runtime = registry.register("test-logic", "org.gms.logic.test.", List.of(Probe.class), host);
            assertThat(runtime.service(Probe.class).value()).isEqualTo(2);
        }
    }

    @Test public void timedOutBarrierCannotApplyAfterTheCallerHasReceivedFailure() throws Exception {
        try (var owner = new GameExecution("slow-owner", new DefaultVersionGate());
             var runtime = runtime(jar(1), new AtomicInteger())) {
            runtime.bind(owner, () -> { });
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var blocked = owner.submit(() -> {
                entered.countDown();
                try { return release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                Path candidate = jar(2);
                assertThat(runtime.reload(candidate).targets()).containsEntry("slow-owner", "FAILED");
                release.countDown(); blocked.get(2, TimeUnit.SECONDS);
                assertThat(owner.call(runtime.service(Probe.class)::value)).isEqualTo(1);
                assertThat(runtime.reload(candidate).targets()).containsEntry("slow-owner", "APPLIED");
                assertThat(owner.call(runtime.service(Probe.class)::value)).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }

    @Test public void contractMismatchAndShadowHostClassAreRejectedBeforeSwitching() throws Exception {
        Path initial = jar(1);
        try (var runtime = runtime(initial, new AtomicInteger())) {
            for (boolean shadow : new boolean[]{false, true}) {
                Path candidate = directory.resolve("invalid-" + shadow + ".jar");
                try (var input = new JarFile(initial.toFile());
                     var output = new JarOutputStream(Files.newOutputStream(candidate))) {
                    for (var entry : input.stream().toList()) {
                        output.putNextEntry(new JarEntry(entry.getName()));
                        try (var bytes = input.getInputStream(entry)) {
                            byte[] content = bytes.readAllBytes();
                            if (!shadow && entry.getName().endsWith("twinkle-module.properties"))
                                content = new String(content, StandardCharsets.US_ASCII)
                                        .replaceAll("fingerprint=[^\\n]+", "fingerprint=incompatible")
                                        .getBytes(StandardCharsets.US_ASCII);
                            output.write(content);
                        }
                        output.closeEntry();
                    }
                    if (shadow) {
                        output.putNextEntry(new JarEntry("org/gms/module/HostServices.class"));
                        output.write(new byte[]{0}); output.closeEntry();
                    }
                }
                assertThatThrownBy(() -> runtime.reload(candidate)).isInstanceOf(IOException.class);
                assertThat(runtime.service(Probe.class).value()).isEqualTo(1);
                assertThat(runtime.liveGenerations()).isEqualTo(1);
            }
        }
    }

    @Test public void startupRecordFailureReportsOnlineSwitchAndSameCandidateCanRepairIt() throws Exception {
        Files.copy(jar(1), directory.resolve("test-logic.jar"));
        Files.createDirectories(directory.resolve("incoming"));
        Files.copy(jar(2), directory.resolve("incoming/test-logic.jar"));
        HostServices host = new HostServices(Map.of(Lifecycle.class, (Lifecycle) () -> { }));
        try (var registry = new ModuleRegistry(directory, Runnable::run)) {
            var runtime = registry.register("test-logic", "org.gms.logic.test.", List.of(Probe.class), host);
            Path obstacle = directory.resolve(".cache/test-logic/active");
            Files.createDirectories(obstacle); Files.writeString(obstacle.resolve("occupied"), "占用");
            assertThat(registry.reload("test-logic").targets()).containsEntry("default", "APPLIED")
                    .containsEntry("startup-record", "FAILED");
            assertThat(runtime.service(Probe.class).value()).isEqualTo(2);
            Files.delete(obstacle.resolve("occupied")); Files.delete(obstacle);
            assertThat(registry.reload("test-logic").targets()).doesNotContainKey("startup-record");
            assertThat(Files.readString(obstacle)).isEqualTo(runtime.digest());
        }
    }

    private ModuleRuntime runtime(Path initial, AtomicInteger closed) throws Exception {
        return new ModuleRuntime("test-logic", "org.gms.logic.test.", List.of(Probe.class),
                new HostServices(Map.of(Lifecycle.class, (Lifecycle) closed::incrementAndGet)), initial,
                directory.resolve("cache"), Runnable::run);
    }

    private Path jar(int value) throws Exception {
        Path classes = directory.resolve("classes-" + value); Files.createDirectories(classes);
        Path source = classes.resolve("Factory.java");
        Files.writeString(source, """
                package org.gms.logic.test;
                import java.util.Map;
                import org.gms.module.*;
                import org.gms.module.ModuleRuntimeTest.*;
                public final class Factory implements BusinessModuleFactory {
                    public BusinessModule create(HostServices host) {
                        return new BusinessModule() {
                            public Map<Class<?>,Object> services() { return Map.of(Probe.class, (Probe) () -> %d); }
                            public void close() { host.require(Lifecycle.class).closed(); }
                        };
                    }
                }
                """.formatted(value));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(), source.toString())).isZero();
        Files.delete(source);
        Path jar = directory.resolve("v" + value + ".jar");
        ModulePackager.main(new String[]{classes.toString(), jar.toString(), "test-logic", "org.gms.logic.test.Factory",
                "org.gms.logic.test.", Probe.class.getName(), Lifecycle.class.getName() + ",org.gms.hotreload.versioned.VersionGate"});
        return jar;
    }
}
