package org.gms.plugin.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 插件扫描器（架构 6.4 数据源定位风格：配置直接指定路径、单份数据、读不到启动报错）。
 *
 * <p>扫描 {@code plugins/} 目录下所有 {@code *.jar}。目录不存在 / 不是目录 → 构造抛
 * {@link IllegalArgumentException}（fail fast，部署者意识到路径问题）。
 */
public final class PluginScanner {

    private static final Logger LOG = LogManager.getLogger(PluginScanner.class);

    private final Path pluginsDir;

    public PluginScanner(Path pluginsDir) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir");
        if (!Files.isDirectory(pluginsDir)) {
            throw new IllegalArgumentException("插件目录不存在或不是目录: " + pluginsDir
                    + "（配置 twinkle.plugin.path 未指定或路径无效，架构 6.4 fail fast）");
        }
    }

    /**
     * @return 目录下全部 {@code *.jar}（按文件名排序保证稳定顺序）
     */
    public List<Path> scanJars() {
        try (var stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描插件目录失败: " + pluginsDir, e);
        }
    }

    public Path pluginsDir() {
        return pluginsDir;
    }
}
