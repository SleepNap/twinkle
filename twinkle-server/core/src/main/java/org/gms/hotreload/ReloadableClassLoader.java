package org.gms.hotreload;


import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;
import java.util.Set;
import lombok.extern.log4j.Log4j2;

/**
 * 可重载模块 classloader（架构 5.1：可重载逻辑隔离在模块 classloader，状态不进该 classloader）。
 *
 * <p>隔离语义：
 * <ul>
 *   <li><b>父优先</b>（delegation to parent）：稳定层类（协议 / 数据模型 / 索引 / 核心机制）由应用
 *       classloader 加载，本 loader 永不重复加载 → 换 loader 时稳定层类身份稳定，不会 CCE。</li>
 *   <li><b>本 loader 负责可替换层</b>（逻辑系统 / 插件）：替换 = 新 loader + 新实例 + 重新绑定接口。</li>
 *   <li><b>可见性边界</b>：可替换层类（在本 loader 内）经接口访问稳定层（架构第三节），若可替换层
 *       反向 import 稳定层具体类，本 loader 会尝试父加载，但**编译期由 ArchUnit 架构测试拦截**。
 *       {@link #isStableClass} 提供运行期兜底。</li>
 * </ul>
 *
 * <p>M4 起 {@link org.gms.plugin.runtime.PluginClassLoader} 继承本类（插件隔离），因此非 final。
 *
 * <p>URL 来源：{@code plugins/} 目录下 jar（内置插件按部署作用域分发）或 classes 目录。
 */
@Log4j2
public class ReloadableClassLoader extends URLClassLoader {



    /**
     * 稳定层包前缀（与 ArchUnit 架构测试共用，见 core 测试 / data 的 architecture 包）。
     *
     * <p>M4 新增 {@code org.gms.plugin.}：plugin-api SDK（贡献点类型/PluginContext 等）是可替换层
     * 经接口访问的稳定面，父优先加载，保证插件换 loader 时 SDK 类身份不变。
     */
    public static final Set<String> STABLE_PACKAGES = Set.of(
            "org.gms.data.",      // 数据模型 + 仓库
            "org.gms.dialect.",   // 方言（基础设施）
            "org.gms.event.",     // 事件总线（基础设施）
            "org.gms.config.",    // 配置门面（基础设施）
            "org.gms.plugin."     // 插件 SDK（可替换层经接口访问的稳定面）
    );

    private final String moduleName;

    public ReloadableClassLoader(String moduleName, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.moduleName = moduleName;
    }

    /**
     * 判断类是否属于稳定层（运行期兜底，架构红线 11）。编译期由 ArchUnit 架构测试拦截。
     */
    public static boolean isStableClass(String className) {
        for (String prefix : STABLE_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 已加载过 → 直接返回（含本 loader 此前加载的类）
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            // 稳定层：父优先（永远由应用 classloader 加载，同类身份稳定 → 换 loader 不 CCE）
            if (isStableClass(name)) {
                return super.loadClass(name, resolve);
            }

            // 可替换层：本地优先（本 loader 持有的逻辑版本为准），本地找不到再父加载
            try {
                Class<?> clazz = findClass(name); // 从本 loader 的 URL 定位并加载
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    /** 卸载钩子：释放到模块的引用（GC 接管旧 loader 及其实例）。 */
    public void dispose() {
        log.info("卸载可重载模块 loader: {}", moduleName);
        // URLClassLoader.close() 关闭打开的 jar 文件句柄
        try {
            close();
        } catch (Exception e) {
            log.warn("关闭模块 loader 异常: {}", moduleName, e);
        }
    }

    @Override
    public String toString() {
        return "ReloadableClassLoader{" + moduleName + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReloadableClassLoader that)) return false;
        return Objects.equals(moduleName, that.moduleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleName);
    }
}
