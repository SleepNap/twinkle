package org.gms.plugin.runtime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.gms.plugin.ContributionHandle;
import org.gms.plugin.PluginContext;
import org.gms.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;


public class PluginDrainTest {
    public interface AsyncCall { CompletionStage<Void> call(); }
    @Test public void asynchronousCallsHoldLeaseAndLateCallsAreRejected(@TempDir Path root) throws Exception {
        var descriptor = descriptor(root);
        var context = new DefaultPluginContext(descriptor, getClass().getClassLoader(), type -> null, router());
        var result = new CompletableFuture<Void>();
        AsyncCall guarded = context.guard(AsyncCall.class, () -> result);
        assertThat(guarded.call()).isSameAs(result);
        context.beginDrain();
        assertThat(context.inFlight()).isEqualTo(1);
        assertThatThrownBy(() -> context.awaitDrained(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(guarded::call).isInstanceOf(RejectedExecutionException.class);
        result.completeExceptionally(new IllegalStateException("test"));
        context.awaitDrained(Duration.ZERO);
        assertThat(context.inFlight()).isZero();
    }
    @Test public void failedCleanupRetainsOriginalContextAndCanBeRetried(@TempDir Path root) throws Exception {
        var descriptor = descriptor(root);
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<PluginContext> original = new AtomicReference<>();
        var manager = new PluginManager(root, (d, context) -> {
            original.set(context);
            context.track(() -> { if (closed.incrementAndGet() == 1) throw new IllegalStateException("busy"); });
            return List.of();
        }, getClass().getClassLoader(), type -> null, router());
        manager.load(descriptor);
        assertThatThrownBy(() -> manager.unload(descriptor.id())).isInstanceOf(IllegalStateException.class);
        assertThat(manager.loaded(descriptor.id()).orElseThrow().context()).isSameAs(original.get());
        assertThatThrownBy(() -> manager.load(descriptor)).isInstanceOf(PluginManager.PluginLoadException.class);
        manager.unload(descriptor.id());
        assertThat(closed.get()).isEqualTo(2);
        assertThat(manager.loadedPlugins()).isEmpty();
        manager.close();
    }
    @Test public void queuedBackgroundTaskCountsBeforeItStarts(@TempDir Path root) throws Exception {
        var context = new DefaultPluginContext(descriptor(root), getClass().getClassLoader(), type -> null, router());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        context.execute(queued::set, () -> { });
        context.beginDrain();
        assertThat(context.inFlight()).isEqualTo(1);
        queued.get().run();
        context.awaitDrained(Duration.ZERO);
    }
    @Test public void failedStartClosesTrackedResources(@TempDir Path root) throws Exception {
        TestPluginJars.writePluginJarWithClasses(root, "com.acme.fail.jar", """
                plugin.id=com.acme.fail
                plugin.name=Fail
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                plugin.main-class=com.acme.fail.Main
                """, java.util.Map.of("com.acme.fail.Main", """
                package com.acme.fail;
                public class Main implements org.gms.plugin.Plugin {
                    public void start(org.gms.plugin.PluginContext context) {
                        var counter = context.getService(java.util.concurrent.atomic.AtomicInteger.class);
                        context.track(() -> counter.incrementAndGet());
                        throw new IllegalStateException("start failed");
                    }
                    public void stop(org.gms.plugin.PluginContext context) { }
                }
                """));
        AtomicInteger closes = new AtomicInteger();
        try (var manager = new PluginManager(root, (d, context) -> List.of(), getClass().getClassLoader(), type -> closes, router())) {
            assertThatThrownBy(() -> manager.load(manager.scan().getFirst())).isInstanceOf(PluginManager.PluginLoadException.class);
            assertThat(closes.get()).isEqualTo(1);
            assertThat(manager.loadedPlugins()).isEmpty();
        }
    }

    @Test public void inlineTaskFailureReleasesItsLeaseExactlyOnce(@TempDir Path root) throws Exception {
        var context = new DefaultPluginContext(descriptor(root), getClass().getClassLoader(), type -> null, router());
        assertThatThrownBy(() -> context.execute(Runnable::run, () -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(context.inFlight()).isZero();
    }

    private PluginDescriptor descriptor(Path root) throws Exception {
        Path jar = TestPluginJars.writeManifestOnlyJar(root, "com.acme.drain.jar", """
                plugin.id=com.acme.drain
                plugin.name=Drain
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """);
        return new ManifestPluginDescriptorParser().parse(jar);
    }
    private ContributionRouter router() {
        return new ContributionRouter() {
            @Override public <T> ContributionHandle register(String type, T value, int version) { return () -> { }; }
        };
    }
}
