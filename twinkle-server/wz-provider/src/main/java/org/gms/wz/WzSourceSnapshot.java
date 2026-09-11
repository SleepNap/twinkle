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
import java.util.UUID;


/** 将源目录冻结为按内容寻址的磁盘快照，延迟解析也只能读取对应代际文件。 */
public record WzSourceSnapshot(Path root, String digest) {
    public static WzSourceSnapshot freeze(Path source) {
        Path cache = source.resolve(".twinkle-snapshots");
        Path staging = cache.resolve("staging-" + UUID.randomUUID());
        try {
            if (!staging.toAbsolutePath().normalize().startsWith(cache.toAbsolutePath().normalize()) || Files.isSymbolicLink(cache))
                throw new IllegalArgumentException("Invalid WZ staging path");
            Files.createDirectories(staging);
            List<Path> files;
            try (var walk = Files.walk(source)) {
                files = walk.filter(path -> !path.startsWith(cache) && Files.isRegularFile(path))
                        .sorted(Comparator.comparing(path -> source.relativize(path).toString())).toList();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (Path file : files) {
                Path relative = source.relativize(file);
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
            if (!hash.equals(hashTree(source, cache))) throw new IllegalStateException("WZ source changed during snapshot creation");
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
