package org.gms.wz;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * WZ 解析结果磁盘缓存（架构 M2-3：秒级重开在 2C2G 上的关键）。
 *
 * <p>Item.wz / Mob.wz 全量解析较慢（数千文件），首次解析后 Java 序列化到磁盘，
 * 重开读缓存省重解析。缓存无自动失效——WZ 数据变更时调用 {@link #clear()} 清除
 * （数据源稳定，手动清可接受）。
 *
 * <p>缓存读取失败（版本变更/损坏）自动回退重新解析，不影响运行。
 */
@Log4j2
public final class WzCache {


    private static final String ITEMS_FILE = "items.ser";
    private static final String MOBS_FILE = "mobs.ser";

    private final Path cacheDir;

    public WzCache(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /** 取物品数据（有缓存读缓存，无则解析并写缓存）。 */
    public Map<Integer, ItemData> items(Supplier<Map<Integer, ItemData>> loader) {
        return cached(ITEMS_FILE, loader);
    }

    /** 取怪物数据（有缓存读缓存，无则解析并写缓存）。 */
    public Map<Integer, MobData> mobs(Supplier<Map<Integer, MobData>> loader) {
        return cached(MOBS_FILE, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> Map<Integer, T> cached(String name, Supplier<Map<Integer, T>> loader) {
        Path file = cacheDir.resolve(name);
        if (Files.isRegularFile(file)) {
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                Map<Integer, T> hit = (Map<Integer, T>) in.readObject();
                log.info("WZ 缓存命中: {}（{} 条）", name, hit.size());
                return hit;
            } catch (IOException | ClassNotFoundException e) {
                log.warn("WZ 缓存读取失败，重新解析: {}", name, e);
            }
        }
        Map<Integer, T> fresh = loader.get();
        save(file, fresh);
        return fresh;
    }

    private void save(Path file, Object obj) {
        try {
            Files.createDirectories(cacheDir);
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) {
                out.writeObject(obj);
            }
            log.info("WZ 缓存写入: {}", file.getFileName());
        } catch (IOException e) {
            log.warn("WZ 缓存写入失败（不影响运行）: {}", file, e);
        }
    }

    /** 清除缓存（WZ 数据源变更后调用）。 */
    public void clear() {
        try {
            Files.deleteIfExists(cacheDir.resolve(ITEMS_FILE));
            Files.deleteIfExists(cacheDir.resolve(MOBS_FILE));
        } catch (IOException e) {
            log.warn("清 WZ 缓存失败: {}", cacheDir, e);
        }
    }
}
