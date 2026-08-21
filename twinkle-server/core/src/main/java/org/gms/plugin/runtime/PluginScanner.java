package org.gms.plugin.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.gms.i18n.I18n;

/**
 * 插件扫描器。
 *
 * <p>扫描 {@code plugins/} 目录下所有 {@code *.jar}。插件是可选组件，目录不存在或不是目录时按无插件处理。
 */
public final class PluginScanner {

    private final Path pluginsDir;

    public PluginScanner(Path pluginsDir) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir");
    }

    /**
     * @return 目录下全部 {@code *.jar}（按文件名排序保证稳定顺序）。
     *         目录不存在 / 不是目录 → 空列表，由启动层统一输出最终结果。
     */
    public List<Path> scanJars() {
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        try (var stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(I18n.message("error.plugin.scan_failed", pluginsDir), e);
        }
    }

    public Path pluginsDir() {
        return pluginsDir;
    }
}
