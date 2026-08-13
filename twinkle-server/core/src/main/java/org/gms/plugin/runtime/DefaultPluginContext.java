package org.gms.plugin.runtime;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
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
 *
 * <p>public：实现公共契约 {@link PluginContext}（plugin-api 可替换层），不得为包私有
 * （红线 12：可见性显式声明，禁止无修饰符裸声明）。
 */
@Log4j2
public final class DefaultPluginContext implements PluginContext {

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
            throw new IllegalArgumentException(I18n.message("error.plugin.service_unavailable", serviceType.getName(), pluginId()));
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
    public void closeTracked() {
        for (AutoCloseable h : tracked) {
            try {
                h.close();
            } catch (Exception e) {
                log.warn(I18n.message("log.plugin.close_handle_failed"), h, pluginId(), e);
            }
        }
        tracked.clear();
    }
}
