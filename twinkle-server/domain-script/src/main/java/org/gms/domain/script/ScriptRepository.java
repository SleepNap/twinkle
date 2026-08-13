package org.gms.domain.script;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;

/**
 * 脚本仓库（架构 6.4：{@code twinkle.script.path} 直接指定脚本目录，单份数据，读不到启动报错）。
 *
 * <p>职责：
 * <ul>
 *   <li>启动期扫描目录下所有 {@code *.js} 文件，构建 {@code key = 文件相对路径（无扩展名）} →
 *       {@link ScriptSource} 的不可变快照。</li>
 *   <li>{@link #loadAll()} 暴露当前快照供脚本引擎使用。</li>
 *   <li>{@link #reload()} 重新扫描文件，与旧快照对比 mtime，更新已变化的项（新增/删除/修改）。
 *       这是 L2 热重载的入口（修改 JS 即生效）。</li>
 * </ul>
 *
 * <p>线程安全：{@link #loadAll()} 返回的 Map 是不可变快照；{@link #reload()} 整体替换内部
 * 引用（{@code volatile}），保证读路径无需加锁。
 */
@Log4j2
public final class ScriptRepository {


    /** 脚本根目录（对应配置 {@code twinkle.script.path}）。 */
    private final Path root;

    /** 纯目录快照（key = 相对路径无扩展名），L2 reload diff 的基线。 */
    private volatile Map<String, ScriptSource> dirSnapshot;

    /** 合并快照（目录 + 插件挂载命名空间），loadAll 返回它。 */
    private volatile Map<String, ScriptSource> snapshot;

    /** 插件挂载的命名空间脚本（namespace → 脚本源），reload 时并入快照保留。 */
    private volatile Map<String, Map<String, ScriptSource>> mounted = Map.of();

    /**
     * @param root 脚本目录（架构 6.4：未指定或读不到启动期报错）
     * @throws IllegalArgumentException 目录不存在或不是目录
     */
    public ScriptRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(I18n.message("error.script.directory_invalid", root));
        }
        this.dirSnapshot = scan();
        this.snapshot = mergedSnapshot(dirSnapshot);
        log.info(I18n.message("log.script.repository_initialized"), snapshot.size(), root);
    }

    /** 扫描目录树，构建新快照（递归所有 *.js，不修改内部状态）。 */
    private Map<String, ScriptSource> scan() {
        List<Path> jsFiles;
        try (var stream = Files.walk(root)) {
            jsFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".js"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(I18n.message("error.script.scan_failed", root), e);
        }
        Map<String, ScriptSource> map = new LinkedHashMap<>();
        for (Path p : jsFiles) {
            ScriptSource src;
            try {
                src = ScriptSource.read(p);
            } catch (IOException e) {
                throw new IllegalStateException(I18n.message("error.script.read_failed", p), e);
            }
            map.put(keyOf(p), src);
        }
        return Map.copyOf(map);
    }

    /** 相对路径（统一 {@code /}）去 {@code .js} 扩展名作为 key，如 {@code quests/intro}。 */
    private String keyOf(Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.endsWith(".js") ? rel.substring(0, rel.length() - 3) : rel;
    }

    /** 当前快照（不可变）。 */
    public Map<String, ScriptSource> loadAll() {
        return snapshot;
    }

    /**
     * 重载：重新扫描目录，更新已变化的条目（挂载的命名空间脚本保留）。
     *
     * @return 重载条目数（新增 + 修改 + 删除）
     */
    public int reload() {
        Map<String, ScriptSource> fresh = scan();
        Map<String, ScriptSource> oldDir = this.dirSnapshot;
        int changed = countDiff(oldDir, fresh);
        this.dirSnapshot = fresh;
        this.snapshot = mergedSnapshot(fresh);
        if (changed > 0) {
            log.info(I18n.message("log.script.reloaded"), changed, this.snapshot.size());
        }
        return changed;
    }

    /**
     * 挂载插件脚本命名空间（架构 7.1 Script 命名空间贡献点）。
     *
     * <p>插件 jar {@code scripts/} 下的脚本经 {@link ScriptSource} 读出后在此挂载，key 前缀为
     * 命名空间（如 {@code acme/foo}）。与目录扫描结果合并进同一不可变快照（volatile 整体替换）。
     *
     * @param namespace 命名空间（如 {@code acme}）
     * @param sources   该命名空间下的脚本源（key 为命名空间内相对路径）
     */
    public void mount(String namespace, Map<String, ScriptSource> sources) {
        Objects.requireNonNull(namespace, "namespace");
        Map<String, Map<String, ScriptSource>> newMounted = new LinkedHashMap<>(this.mounted);
        newMounted.put(namespace, Map.copyOf(sources));
        this.mounted = Map.copyOf(newMounted);
        this.snapshot = mergedSnapshot(this.dirSnapshot);
        log.info(I18n.message("log.script.namespace_mounted"), namespace, sources.size());
    }

    /**
     * 卸载插件脚本命名空间（插件 unload 时调用，幂等）。
     */
    public void unmount(String namespace) {
        Map<String, Map<String, ScriptSource>> newMounted = new LinkedHashMap<>(this.mounted);
        if (newMounted.remove(namespace) == null) {
            return; // 未挂载，幂等
        }
        this.mounted = Map.copyOf(newMounted);
        this.snapshot = mergedSnapshot(this.dirSnapshot);
        log.info(I18n.message("log.script.namespace_unmounted"), namespace);
    }

    /** 目录快照 + 全部挂载命名空间脚本合并为最终快照。 */
    private Map<String, ScriptSource> mergedSnapshot(Map<String, ScriptSource> base) {
        Map<String, ScriptSource> merged = new LinkedHashMap<>(base);
        for (var nsEntry : mounted.entrySet()) {
            String ns = nsEntry.getKey();
            for (var srcEntry : nsEntry.getValue().entrySet()) {
                merged.put(ns + "/" + srcEntry.getKey(), srcEntry.getValue());
            }
        }
        return Map.copyOf(merged);
    }

    private int countDiff(Map<String, ScriptSource> old, Map<String, ScriptSource> fresh) {
        int diff = 0;
        for (var e : fresh.entrySet()) {
            ScriptSource prev = old.get(e.getKey());
            if (prev == null || prev.lastModified() != e.getValue().lastModified()) {
                diff++;
            }
        }
        diff += old.size() - intersectSize(old, fresh);
        return diff;
    }

    private int intersectSize(Map<String, ScriptSource> a, Map<String, ScriptSource> b) {
        int n = 0;
        for (String k : a.keySet()) {
            if (b.containsKey(k)) {
                n++;
            }
        }
        return n;
    }

    /** 脚本根目录（用于诊断/日志）。 */
    public Path root() {
        return root;
    }
}
