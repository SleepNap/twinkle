package org.gms.wz;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/** 将源目录冻结为按内容寻址的磁盘快照，延迟解析也只能读取对应代际文件。 */
public record WzSourceSnapshot(Path root, String digest) {
    /** 默认缓存目录名；位于源目录内，源目录必须是可写的运行数据目录。 */
    private static final String CACHE_DIRECTORY = ".twinkle-snapshots";

    public static WzSourceSnapshot freeze(Path source) {
        Path root = source.toAbsolutePath().normalize();
        return freeze(root, root.resolve(CACHE_DIRECTORY));
    }

    /**
     * 冻结到显式缓存目录。
     *
     * <p>WZ 源目录可能是另一套仓库或只读共享目录，此时缓存必须落在源目录之外，
     * 避免在别人的工作区里生成大量代际文件。
     */
    public static WzSourceSnapshot freeze(Path source, Path cacheRoot) {
        Path root = source.toAbsolutePath().normalize();
        Path cache = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        Path staging = cache.resolve("staging-" + UUID.randomUUID());
        try {
            if (!staging.normalize().startsWith(cache) || Files.isSymbolicLink(cache))
                throw new IllegalArgumentException("Invalid WZ staging path");
            Files.createDirectories(staging);
            List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(path -> !path.startsWith(cache) && Files.isRegularFile(path))
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (Path file : files) {
                Path relative = root.relativize(file);
                byte[] name = relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(8).putLong(Files.size(file)).array());
                Path output = staging.resolve(relative);
                Files.createDirectories(output.getParent());
                try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(output)) {
                    for (int n; (n = in.read(buffer)) != -1;) { digest.update(buffer, 0, n); out.write(buffer, 0, n); }
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (!hash.equals(hashTree(root, cache))) throw new IllegalStateException("WZ source changed during snapshot creation");
            Path destination = cache.resolve(hash);
            if (Files.exists(destination) && !hash.equals(hashTree(destination, null)))
                throw new IllegalStateException("WZ snapshot cache is corrupt");
            if (!Files.exists(destination)) Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            return new WzSourceSnapshot(destination, hash);
        } catch (Exception error) { throw new IllegalStateException("Cannot freeze WZ source", error); }
        finally {
            if (Files.exists(staging)) {
                try (var walk = Files.walk(staging)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (Exception error) { throw new IllegalStateException("Cannot clean WZ staging", error); }
            }
        }
    }
    private static String hashTree(Path root, Path excluded) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> (excluded == null || !path.startsWith(excluded)) && Files.isRegularFile(path))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
        }
        byte[] buffer = new byte[65536];
        for (Path file : files) {
            byte[] name = root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array()); digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(Files.size(file)).array());
            try (InputStream in = Files.newInputStream(file)) {
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

}
