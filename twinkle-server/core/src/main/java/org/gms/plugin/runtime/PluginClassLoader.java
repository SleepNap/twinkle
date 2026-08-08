package org.gms.plugin.runtime;

import org.gms.hotreload.ReloadableClassLoader;

import java.net.URL;

/**
 * 插件 classloader（架构 7.3 可见性边界：绝不能加载/遮蔽稳定层类，否则 CCE）。
 *
 * <p>策略：<b>宿主包（{@code org.gms.*}）一律父优先</b>——插件 jar 里即使塞了一个
 * {@code org.gms.data.entity.Character}，加载时直接由父（应用 classloader）加载宿主真身，
 * 结构上不可能遮蔽稳定层类。插件自有代码（{@code com.acme.*} 等非 org.gms 包）本地优先，
 * 换 loader = 新实例，与 L3 模块替换同构（架构 5.1）。
 *
 * <p>红线 11 的编译期强制由 ArchUnit（规则 3）承担，本 loader 是运行期兜底。
 */
public final class PluginClassLoader extends ReloadableClassLoader {

    public PluginClassLoader(String moduleName, URL[] urls, ClassLoader parent) {
        super(moduleName, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            if (name.startsWith("org.gms.")) {
                // 宿主类（含 SDK / 稳定层）：只允许父加载，本 loader 拒绝本地加载 → 永不遮蔽
                return super.loadClass(name, resolve);
            }
            try {
                return findClass(name); // 插件自有类 / 自带第三方库：本地优先
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }
}
