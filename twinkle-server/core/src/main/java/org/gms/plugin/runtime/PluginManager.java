package org.gms.plugin.runtime;

import lombok.extern.log4j.Log4j2;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.plugin.ContributionHandle;
import org.gms.plugin.Plugin;
import org.gms.plugin.PluginDescriptor;
import org.gms.plugin.PluginHost;
import org.gms.plugin.SdkVersion;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 插件管理器（架构 7.1/7.2：插件系统 = 热重载系统；可装卸 + classloader 隔离 + SDK 版本化）。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #load}：新 {@link PluginClassLoader} → SDK 版本校验 → 实例化主类 → 声明式贡献点
 *       （{@link PluginHost#applyContributions}）→ 命令式 {@link Plugin#start}。</li>
 *   <li>{@link #unload}：{@link Plugin#stop} → 关闭全部贡献点句柄 → 释放 loader。</li>
 *   <li>{@link #reload}：unload 旧 + 换代版本门 + 渐进重载（L3 纪律，见实现）。</li>
 * </ul>
 *
 * <p>线程安全：加载/卸载为管理侧低频操作，{@code ConcurrentMap} 兜底并发访问。
 */
@Log4j2
public final class PluginManager implements Closeable {



    /** 插件目录（配置 {@code twinkle.plugin.path}）。 */
    private final Path pluginsDir;
    private final PluginHost host;
    private final ClassLoader hostClassLoader;
    /** 宿主服务解析（插件 getService 用；由装配层提供）。 */
    private final Function<Class<?>, Object> serviceResolver;
    /** 命令式贡献点路由（装配层提供）。 */
    private final ContributionRouter contributionRouter;
    /** L3 版本门（reload 换代，旧插件迟到写被拒）。 */
    private final VersionGate versionGate;
    /** 按实体渐进重载（插件 reload 时中断在途长操作）。 */
    private final EntityReloadService entityReloadService;

    private final ConcurrentMap<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();

    public PluginManager(Path pluginsDir, PluginHost host, ClassLoader hostClassLoader,
                         Function<Class<?>, Object> serviceResolver,
                         ContributionRouter contributionRouter) {
        this(pluginsDir, host, hostClassLoader, serviceResolver, contributionRouter, null, null);
    }

    public PluginManager(Path pluginsDir, PluginHost host, ClassLoader hostClassLoader,
                         Function<Class<?>, Object> serviceResolver,
                         ContributionRouter contributionRouter,
                         VersionGate versionGate,
                         EntityReloadService entityReloadService) {
        this.pluginsDir = pluginsDir;
        this.host = host;
        this.hostClassLoader = hostClassLoader;
        this.serviceResolver = serviceResolver;
        this.contributionRouter = contributionRouter;
        this.versionGate = versionGate;
        this.entityReloadService = entityReloadService;
    }

    /** 扫描插件目录全部 jar 并解析 manifest。 */
    public List<PluginDescriptor> scan() {
        PluginScanner scanner = new PluginScanner(pluginsDir);
        ManifestPluginDescriptorParser parser = new ManifestPluginDescriptorParser();
        List<PluginDescriptor> descriptors = new ArrayList<>();
        for (Path jar : scanner.scanJars()) {
            try {
                descriptors.add(parser.parse(jar));
            } catch (ManifestPluginDescriptorParser.PluginDescriptorException e) {
                // 单个插件解析失败：记录日志，不拖垮整批（可人工修复后 reload）
                log.error("插件解析失败，拒载: {}", jar, e);
            }
        }
        return descriptors;
    }

    /**
     * 加载插件。
     *
     * @throws PluginLoadException SDK 版本不兼容 / 主类实例化失败 / 贡献点应用失败 / start 抛异常
     */
    public LoadedPlugin load(PluginDescriptor descriptor) throws PluginLoadException {
        Path jar = findJar(descriptor.id());
        if (jar == null) {
            throw new PluginLoadException("插件 jar 未找到: " + descriptor.id() + "（目录=" + pluginsDir + "）", null);
        }
        if (loaded.containsKey(descriptor.id())) {
            throw new PluginLoadException("插件已加载: " + descriptor.id() + "（请用 reload）", null);
        }

        validateSdk(descriptor);

        PluginClassLoader loader;
        try {
            loader = new PluginClassLoader(descriptor.id(), new java.net.URL[]{jar.toUri().toURL()}, hostClassLoader);
        } catch (MalformedURLException e) {
            throw new PluginLoadException("插件 jar URL 非法: " + jar, e);
        }

        Plugin instance = null;
        try {
            if (descriptor.mainClass() != null && !descriptor.mainClass().isBlank()) {
                instance = instantiateMain(loader, descriptor.mainClass());
            }

            DefaultPluginContext context = new DefaultPluginContext(
                    descriptor, loader, serviceResolver, contributionRouter);

            // 声明式贡献点（manifest 驱动）→ 宿主落各注册表
            List<ContributionHandle> contributions = host.applyContributions(descriptor, context);

            // 命令式贡献点（插件主类 start）
            if (instance != null) {
                instance.start(context);
            }

            LoadedPlugin loadedPlugin = new LoadedPlugin(descriptor, loader, instance, contributions);
            loaded.put(descriptor.id(), loadedPlugin);
            log.info("插件已加载: {} v{}（scope={}，贡献点 {} 项）", descriptor.id(), descriptor.version(),
                    descriptor.scope(), contributions.size());
            return loadedPlugin;
        } catch (PluginLoadException e) {
            loader.dispose();
            throw e;
        } catch (Exception e) {
            loader.dispose();
            throw new PluginLoadException("插件加载失败: " + descriptor.id(), e);
        }
    }

    /**
     * 卸载插件（幂等：未加载则 no-op）。
     */
    public void unload(String pluginId) {
        LoadedPlugin plugin = loaded.remove(pluginId);
        if (plugin == null) {
            return;
        }
        // 1) 命令式 stop
        if (plugin.instance() != null) {
            try {
                plugin.instance().stop(contextFor(plugin));
            } catch (Exception e) {
                log.error("插件 stop 异常: {}", pluginId, e);
            }
        }
        // 2) 声明式 + 命令式贡献点统一回滚
        for (ContributionHandle handle : plugin.contributions()) {
            try {
                handle.close();
            } catch (Exception e) {
                log.error("插件贡献点回滚异常: {}", pluginId, e);
            }
        }
        // 3) 释放 classloader（关闭 jar 句柄）
        plugin.classLoader().dispose();
        log.info("插件已卸载: {}", pluginId);
    }

    public List<LoadedPlugin> loadedPlugins() {
        return List.copyOf(loaded.values());
    }

    /**
     * 插件热重载（架构 5.2 L3 / 5.3：插件系统 = 热重载系统，与 L3 纪律对齐）。
     *
     * <ol>
     *   <li>卸载旧版（stop + 贡献点回滚 + 释放 loader）。</li>
     *   <li><b>换代版本门</b>：{@code versionGate.onReload()}，旧插件迟到写此后被拒
     *       （红线 12/架构 5.3，配合按实体渐进重载）。</li>
     *   <li>中断在途长操作（交易等显式中断 + 回滚，兜住插件碰长操作的极端情况）。</li>
     *   <li>加载新版（新 loader + 贡献点以更高版本注册）。</li>
     * </ol>
     *
     * <p>未注入版本门/渐进重载（M4 测试装配）时退化为 unload + load（纯装卸）。
     */
    public LoadedPlugin reload(PluginDescriptor descriptor) {
        unload(descriptor.id());
        if (versionGate != null && entityReloadService != null) {
            // reloadAllInFlight 内部已换代版本门（coordinator.advanceVersion → gate.onReload）
            // + 中断在途长操作；这里不重复 onReload，避免版本跳两号
            var result = entityReloadService.reloadAllInFlight(id -> true);
            log.info("插件重载：版本门换代 → v{}（安全切换 {}，中断 {}）",
                    result.newVersion(), result.safeSwitched(), result.interrupted());
        }
        return load(descriptor);
    }

    public Optional<LoadedPlugin> loaded(String pluginId) {
        return Optional.ofNullable(loaded.get(pluginId));
    }

    /** 卸载全部插件（进程关停用）。 */
    @Override
    public void close() throws IOException {
        for (String id : List.copyOf(loaded.keySet())) {
            unload(id);
        }
    }

    private void validateSdk(PluginDescriptor descriptor) {
        int sdk = descriptor.sdkVersion();
        if (sdk < SdkVersion.MIN_COMPATIBLE || sdk > SdkVersion.CURRENT) {
            throw new PluginLoadException("插件 SDK 版本不兼容: " + descriptor.id()
                    + " 声明 sdk-version=" + sdk + "，宿主支持 [" + SdkVersion.MIN_COMPATIBLE + ", " + SdkVersion.CURRENT + "]", null);
        }
    }

    @SuppressWarnings("unchecked")
    private Plugin instantiateMain(PluginClassLoader loader, String mainClass) throws PluginLoadException {
        try {
            Class<?> clazz = Class.forName(mainClass, true, loader);
            return (Plugin) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new PluginLoadException("插件主类实例化失败: " + mainClass, e);
        }
    }

    /** 从插件目录定位某 id 对应的 jar。id 与 jar 文件名前缀匹配（如 com.acme.boss → com.acme.boss-*.jar）。 */
    private Path findJar(String pluginId) {
        PluginScanner scanner = new PluginScanner(pluginsDir);
        for (Path jar : scanner.scanJars()) {
            String fileName = jar.getFileName().toString();
            if (fileName.equals(pluginId + ".jar") || fileName.startsWith(pluginId + "-")) {
                return jar;
            }
        }
        return null;
    }

    /** 重建上下文（unload 的 stop 回调用：插件 stop 可能需要 context）。 */
    private DefaultPluginContext contextFor(LoadedPlugin plugin) {
        return new DefaultPluginContext(plugin.descriptor(), plugin.classLoader(), serviceResolver, contributionRouter);
    }

    /** 插件加载失败（SDK 不兼容 / 实例化 / 贡献点 / start）。 */
    public static final class PluginLoadException extends RuntimeException {
        public PluginLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
