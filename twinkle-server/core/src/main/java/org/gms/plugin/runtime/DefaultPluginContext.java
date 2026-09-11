package org.gms.plugin.runtime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.gms.i18n.I18n;
import org.gms.plugin.ContributionHandle;
import org.gms.plugin.ContributionRegistrar;
import org.gms.plugin.PluginContext;
import org.gms.plugin.PluginDescriptor;


/** 每个插件唯一的生命周期上下文；关闭接入、排空调用、释放句柄分别执行。 */
public final class DefaultPluginContext implements PluginContext {
    private final PluginDescriptor descriptor;
    private final ClassLoader classLoader;
    private final Function<Class<?>, Object> serviceResolver;
    private final ContributionRouter contributionRouter;
    private final List<AutoCloseable> tracked = new ArrayList<>();
    private boolean draining;
    private int inFlight;
    private boolean stopped;

    public DefaultPluginContext(PluginDescriptor descriptor, ClassLoader classLoader,
                                Function<Class<?>, Object> serviceResolver, ContributionRouter contributionRouter) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.classLoader = Objects.requireNonNull(classLoader);
        this.serviceResolver = Objects.requireNonNull(serviceResolver);
        this.contributionRouter = Objects.requireNonNull(contributionRouter);
    }
    @Override public String pluginId() { return descriptor.id(); }
    @Override public int sdkVersion() { return descriptor.sdkVersion(); }
    @Override public PluginDescriptor descriptor() { return descriptor; }
    @Override public ClassLoader classLoader() { return classLoader; }
    @Override public URL resource(String path) { return classLoader.getResource(path); }
    @Override public <T> T getService(Class<T> type) {
        Object service = serviceResolver.apply(type);
        if (service == null) throw new IllegalArgumentException(I18n.message("error.plugin.service_unavailable", type.getName(), pluginId()));
        return type.cast(service);
    }
    @Override public ContributionRegistrar contributions() {
        return new ContributionRegistrar() {
            @Override public <T> ContributionHandle register(String type, T contribution, int version) {
                synchronized (DefaultPluginContext.this) {
                    requireActive();
                    ContributionHandle handle = contributionRouter.register(type, guardInterfaces(contribution), version);
                    return trackContribution(handle);
                }
            }
            @Override public <T> ContributionHandle subscribe(String target, Class<T> type, Consumer<T> consumer) {
                synchronized (DefaultPluginContext.this) {
                    requireActive();
                    Consumer<T> guarded = event -> {
                        if (!tryEnter()) return;
                        try { consumer.accept(event); } finally { leave(); }
                    };
                    return trackContribution(contributionRouter.subscribe(target, type, guarded));
                }
            }
        };
    }
    /** 声明式和命令式句柄使用同一清理记录，重复关闭安全。 */
    public synchronized ContributionHandle trackContribution(ContributionHandle handle) {
        requireActive();
        ContributionHandle once = new ContributionHandle() {
            private boolean closed;
            @Override public synchronized void close() {
                if (!closed) { handle.close(); closed = true; }
            }
        };
        tracked.add(once);
        return once;
    }
    @Override public synchronized AutoCloseable track(AutoCloseable handle) {
        requireActive();
        tracked.add(Objects.requireNonNull(handle));
        return handle;
    }
    @Override public <T> T guard(Class<T> type, T implementation) {
        if (!type.isInterface()) throw new IllegalArgumentException("Plugin contribution must use an interface: " + type.getName());
        return type.cast(proxy(implementation, new Class<?>[]{type}));
    }
    /** 逻辑贡献点须显式实现宿主接口，防止具体插件对象外泄。 */
    @SuppressWarnings("unchecked")
    public <T> T guardInterfaces(T implementation) {
        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> type = implementation.getClass(); type != null; type = type.getSuperclass())
            interfaces.addAll(List.of(type.getInterfaces()));
        if (interfaces.isEmpty()) throw new IllegalArgumentException("Plugin contribution has no interface");
        return (T) proxy(implementation, interfaces.stream().distinct().toArray(Class<?>[]::new));
    }
    private Object proxy(Object implementation, Class<?>[] interfaces) {
        return Proxy.newProxyInstance(classLoader, interfaces, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "Plugin contribution: " + pluginId();
            };
            enter();
            boolean async = false;
            try {
                Object value = method.invoke(implementation, args);
                if (value instanceof CompletionStage<?> stage) {
                    async = true;
                    stage.whenComplete((ignored, error) -> leave());
                }
                return value;
            } catch (InvocationTargetException error) { throw error.getCause(); }
            finally { if (!async) leave(); }
        });
    }
    @Override public void execute(Executor executor, Runnable task) {
        enter();
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) leave(); };
        try { executor.execute(() -> { try { task.run(); } finally { release.run(); } }); }
        catch (RuntimeException | Error error) { release.run(); throw error; }
    }
    private synchronized boolean tryEnter() {
        if (draining) return false;
        inFlight++;
        return true;
    }
    private void enter() {
        if (!tryEnter()) throw new RejectedExecutionException(I18n.message("error.plugin.draining", pluginId()));
    }
    private synchronized void leave() { inFlight--; notifyAll(); }
    private void requireActive() {
        if (draining) throw new RejectedExecutionException(I18n.message("error.plugin.draining", pluginId()));
    }
    public synchronized void beginDrain() { draining = true; }
    public synchronized int inFlight() { return inFlight; }
    public synchronized boolean stopped() { return stopped; }
    public synchronized void markStopped() { stopped = true; }
    public synchronized void awaitDrained(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException(I18n.message("error.plugin.drain_timeout", pluginId(), inFlight));
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }
    }
    /** 清理失败的句柄保留，阻止释放加载器；下次卸载只重试未完成项。 */
    public void closeTracked() {
        List<AutoCloseable> copy;
        synchronized (this) { copy = new ArrayList<>(tracked); }
        RuntimeException failure = null;
        for (int i = copy.size() - 1; i >= 0; i--) {
            AutoCloseable handle = copy.get(i);
            try { handle.close(); synchronized (this) { tracked.remove(handle); } }
            catch (Exception error) {
                if (failure == null) failure = new IllegalStateException(I18n.message("error.plugin.cleanup_failed", pluginId()));
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
