package org.gms.channel;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 怪物掉落配置：{@code mobId=meso:min:max,itemId:chance:minQty:maxQty,...}。
 *
 * <p>示例：{@code 100100=meso:10:30,2000000:100:1:2} 表示 100100 必掉 10–30 金币，
 * 并以 100% 概率掉 1–2 个 2000000。概率是 1–100 的百分点；每个怪物最多 8 条配置。
 * 未配置的怪物不掉落；物品是否存在、是否可交易由运行时物品目录和
 * {@link GroundDropService} 再校验。可用 {@code twinkle.gameplay.drop-path} 指定外部配置。
 */
@Singleton
public final class MonsterDropCatalog {

    public record MesoDrop(int min, int max) {
    }

    public record ItemDrop(int itemId, int chance, int min, int max) {
    }

    public record Table(MesoDrop meso, List<ItemDrop> items) {
    }

    private static final int MAX_ENTRIES = 8;
    private static final int MAX_MESO = 1_000_000;

    private final Map<Integer, Table> tables;

    @Inject
    public MonsterDropCatalog(@Property(name = "twinkle.gameplay.drop-path", defaultValue = "") String configuredPath) {
        Properties properties = new Properties();
        try (InputStream stream = configuredPath.isBlank()
                ? MonsterDropCatalog.class.getResourceAsStream("/gameplay/drops.properties")
                : Files.newInputStream(Path.of(configuredPath))) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load monster drop configuration", error);
        }
        Map<Integer, Table> loaded = new TreeMap<>();
        properties.forEach((key, value) -> loaded.put(parseMobId(key.toString()), parseTable(value.toString())));
        this.tables = Map.copyOf(loaded);
    }

    public Table table(int mobId) {
        return tables.get(mobId);
    }

    private static int parseMobId(String raw) {
        try {
            int mobId = Integer.parseInt(raw.trim());
            if (mobId <= 0) {
                throw new IllegalArgumentException("Invalid mob id: " + raw);
            }
            return mobId;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid mob id: " + raw, error);
        }
    }

    private static Table parseTable(String raw) {
        MesoDrop meso = null;
        List<ItemDrop> items = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":", -1);
            if (parts.length == 3 && "meso".equals(parts[0])) {
                int min = number(parts[1], "meso min");
                int max = number(parts[2], "meso max");
                if (min < 1 || max < min || max > MAX_MESO || meso != null) {
                    throw new IllegalArgumentException("Invalid meso drop: " + entry);
                }
                meso = new MesoDrop(min, max);
            } else if (parts.length == 4) {
                int itemId = number(parts[0], "item id");
                int chance = number(parts[1], "drop chance");
                int min = number(parts[2], "item min");
                int max = number(parts[3], "item max");
                if (itemId <= 0 || chance < 1 || chance > 100 || min < 1 || max < min
                        || max > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid item drop: " + entry);
                }
                items.add(new ItemDrop(itemId, chance, min, max));
            } else {
                throw new IllegalArgumentException("Invalid drop entry: " + entry);
            }
            if (items.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many drop entries: " + raw);
            }
        }
        if (meso == null && items.isEmpty()) {
            throw new IllegalArgumentException("Empty drop table: " + raw);
        }
        if (items.size() + (meso == null ? 0 : 1) > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many drop entries: " + raw);
        }
        return new Table(meso, List.copyOf(items));
    }

    private static int number(String raw, String field) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + field + ": " + raw, error);
        }
    }
}
