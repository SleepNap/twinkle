package org.gms.wz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单个 WZ 目录的按需 XML 节点缓存。
 *
 * <p>每次全局重载都会创建全新的 catalog，因此旧节点缓存会随旧快照整体淘汰。Name、Skill、
 * Buff、Quest 及未来尚未建立业务投影的数据都可先通过本类访问，不需要磁盘序列化缓存。
 */
public final class WzNodeCatalog {

    private final Path root;
    private final ConcurrentMap<String, WzNode> nodes = new ConcurrentHashMap<>();

    public WzNodeCatalog(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Optional<WzNode> get(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(nodes.computeIfAbsent(normalizedKey(file), ignored -> WzXmlParser.parse(file)));
    }

    public int cachedCount() {
        return nodes.size();
    }

    public Path root() {
        return root;
    }

    private Path resolve(String relativePath) {
        Path file = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("WZ path escapes resource root: " + relativePath);
        }
        return file;
    }

    private String normalizedKey(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
