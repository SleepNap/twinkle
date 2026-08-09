package org.gms.wz;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.item.ItemData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 物品数据加载器（架构 6.4：`twinkle.wz.path` 直接指定 WZ 目录，单份数据）。
 *
 * <p>遍历 {@code Item.wz/{Consume,Etc,Install,Cash,Special,Equip}} 下所有 {@code *.img.xml}，
 * 每个 item imgdir 填 {@link ItemData}。消费类效果取 {@code spec} 节点，装备能力取
 * {@code info} 节点的已知能力键（当前北斗 WZ 的 Equip 目录为空，结构预留）。
 *
 * <p>读不到的目录跳过（各发行版解包范围不一），解析失败抛 {@link IllegalStateException}。
 */
@Log4j2
public final class ItemLoader {


    /** 装备能力键（Equip info 节点，M2-3 预留，Equip 数据就绪时生效）。 */
    private static final Set<String> EQUIP_STATS = Set.of(
            "str", "dex", "int", "luk", "hp", "mp",
            "watk", "matk", "wdef", "mdef", "acc", "avoid", "jump", "speed", "hands");

    private static final String[] CATEGORIES = {"Consume", "Etc", "Install", "Cash", "Special", "Equip"};

    private final Path wzRoot;

    public ItemLoader(Path wzRoot) {
        this.wzRoot = Objects.requireNonNull(wzRoot, "wzRoot");
    }

    /** 解析 Item.wz 全部物品（id → ItemData）。 */
    public Map<Integer, ItemData> loadAll() {
        Path itemWz = wzRoot.resolve("Item.wz");
        Map<Integer, ItemData> items = new HashMap<>();
        for (String category : CATEGORIES) {
            Path dir = itemWz.resolve(category);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.walk(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".img.xml"))
                        .forEach(p -> parseFile(p, items));
            } catch (IOException e) {
                throw new IllegalStateException("遍历 Item.wz/" + category + " 失败: " + dir, e);
            }
        }
        log.info("Item.wz 解析完成：{} 个物品（根={}）", items.size(), wzRoot);
        return items;
    }

    private void parseFile(Path file, Map<Integer, ItemData> items) {
        WzNode root = WzXmlParser.parse(file);
        root.children().forEach((itemId, node) -> {
            if (!isNumeric(itemId)) {
                return;
            }
            ItemData data = new ItemData(Integer.parseInt(itemId));
            node.child("info").ifPresent(info -> {
                info.getInt("price").ifPresent(data::setPrice);
                info.getInt("slotMax").ifPresent(data::setSlotMax);
                info.getInt("reqLevel").ifPresent(data::setReqLevel);
                info.getInt("tradeBlock").ifPresent(tb -> data.setTradeBlock(tb == 1));
                info.values().forEach((k, v) -> {
                    if (EQUIP_STATS.contains(k) && isNumeric(v)) {
                        data.putStat(k, Integer.parseInt(v));
                    }
                });
            });
            node.child("spec").ifPresent(spec -> spec.values().forEach((k, v) -> {
                if (isNumeric(v)) {
                    data.putStat(k, Integer.parseInt(v));
                }
            }));
            items.put(data.getItemId(), data);
        });
    }

    private static boolean isNumeric(String s) {
        return s != null && s.matches("-?\\d+");
    }
}
