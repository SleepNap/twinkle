package org.gms.plugin.runtime;

import org.gms.plugin.ContributionHandle;
import org.gms.plugin.ContributionRegistrar;
import org.gms.plugin.PluginContext;
import org.gms.plugin.PluginDescriptor;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 插件上下文实现（core 插件运行时；宿主在加载期构建并传给插件）。
 *
 * <p>贡献点注册门面在此落地：{@code register} 经宿主 {@link org.gms.plugin.PluginHost} 落进对应注册表，
 * 返回的 {@link ContributionHandle} 被 {@code tracked} 收集——卸载时统一回滚（配合 track 的
 * 插件自有句柄一起关闭）。
 */
final class DefaultPluginContext implements PluginContext {

    private final PluginDescriptor descriptor;
    private final ClassLoader classLoader;
    private final Function<Class<?>, Object> serviceResolver;
    private final ContributionRouter contributionRouter;
    private final List<AutoCloseable> tracked = new ArrayList<>();

    /**
     * @param serviceResolver 宿主服务解析（按接口类型 → 实现实例）
     * @param contributionRouter 宿主贡献点注册路由（按贡献点类型 → 注册表）
     */
    DefaultPluginContext(PluginDescriptor descriptor, ClassLoader classLoader,
                         Function<Class<?>, Object> serviceResolver,
                         ContributionRouter contributionRouter) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.classLoader = Objects.requireNonNull(classLoader);
        this.serviceResolver = Objects.requireNonNull(serviceResolver);
        this.contributionRouter = Objects.requireNonNull(contributionRouter);
    }

    @Override
    public String pluginId() {
        return descriptor.id();
    }

    @Override
    public int sdkVersion() {
        return descriptor.sdkVersion();
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public ContributionRegistrar contributions() {
        return new ContributionRegistrar() {
            @Override
            public <T> ContributionHandle register(String contributionType, T contribution, int version) {
                ContributionHandle handle = contributionRouter.register(contributionType, contribution, version);
                tracked.add(handle);
                return handle;
            }

            @Override
            public <T> ContributionHandle subscribe(String target, Class<T> eventType, Consumer<T> consumer) {
                ContributionHandle handle = contributionRouter.subscribe(target, eventType, consumer);
                tracked.add(handle);
                return handle;
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceType) {
        Object service = serviceResolver.apply(serviceType);
        if (service == null) {
            throw new IllegalArgumentException("宿主服务不可用: " + serviceType.getName() + "（插件 " + pluginId() + "）");
        }
        return (T) service;
    }

    @Override
    public URL resource(String path) {
        return classLoader.getResource(path);
    }

    @Override
    public AutoCloseable track(AutoCloseable handle) {
        tracked.add(handle);
        return handle;
    }

    /** 关闭全部已跟踪句柄（插件卸载时调用；先 stop 后关句柄）。 */
    void closeTracked() {
        for (AutoCloseable h : tracked) {
            try {
                h.close();
            } catch (Exception e) {
                org.apache.logging.log4j.LogManager.getLogger(DefaultPluginContext.class)
                        .warn("关闭插件句柄异常: {}（插件 {}）", h, pluginId(), e);
            }
        }
        tracked.clear();
    }

    /**
     * 贡献点注册路由（宿主侧实现按贡献点类型落进各注册表）。
     *
     * <p>接口在 core（插件运行时可见），实现放 bootstrap 装配层（宿主看见各注册表）。
     */
    @FunctionalInterface
    interface ContributionRouter {
        <T> ContributionHandle register(String contributionType, T contribution, int version);

        default <T> ContributionHandle subscribe(String target, Class<T> eventType, Consumer<T> consumer) {
            throw new UnsupportedOperationException("命令式事件订阅未接线（由装配层宿主提供）");
        }
    }
}
