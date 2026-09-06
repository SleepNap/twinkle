package org.gms.wz;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.item.EquipmentData;
import org.gms.i18n.I18n;

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
 * {@code info} 节点；Character.wz 下的独立装备文件补充穿戴条件与 inc 属性。
 *
 * <p>读不到的目录跳过（各发行版解包范围不一），解析失败抛 {@link IllegalStateException}。
 */
public final class ItemLoader {


    /** 统一后的装备能力键；Character.wz 的 incXXX 在读取时转换。 */
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
                throw new IllegalStateException(I18n.message("error.wz.item_walk_failed", category, dir), e);
            }
        }
        Path characters = wzRoot.resolve("Character.wz");
        if (Files.isDirectory(characters)) {
            try (var files = Files.walk(characters)) {
                files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("0?1\\d{6}\\.img\\.xml"))
                        .sorted().forEach(p -> parseEquipment(p, items));
            } catch (IOException e) {
                throw new IllegalStateException(I18n.message("error.wz.item_walk_failed", "Character", characters), e);
            }
        }
        return items;
    }

    /** 文件名决定物品编号；只读取根 info，动画和贴图不会被当成物品定义。 */
    private void parseEquipment(Path file, Map<Integer, ItemData> items) {
        String name = file.getFileName().toString();
        int id = Integer.parseInt(name.substring(0, name.indexOf('.')));
        WzNode info = WzXmlParser.parse(file).child("info").orElse(null);
        if (info == null) return;
        ItemData data = new ItemData(id);
        data.setSlotMax(1);
        data.setPrice(info.getInt("price").orElse(0));
        data.setReqLevel(info.getInt("reqLevel").orElse(0));
        data.setTradeBlock(info.getInt("tradeBlock").orElse(0) != 0);
        data.setEquipment(new EquipmentData(info.getString("islot").orElse(""),
                info.getInt("cash").orElse(0) != 0, info.getInt("reqJob").orElse(0),
                info.getInt("reqSTR").orElse(0), info.getInt("reqDEX").orElse(0),
                info.getInt("reqINT").orElse(0), info.getInt("reqLUK").orElse(0),
                info.getInt("reqPOP").orElse(0), info.getInt("gender").orElse(id / 1000 % 10),
                info.getInt("equipTradeBlock").orElse(0) != 0,
                info.getInt("onlyEquip").orElse(0) != 0, info.getInt("tuc").orElse(0)));
        Map<String, String> names = Map.ofEntries(
                Map.entry("incSTR", "str"), Map.entry("incDEX", "dex"), Map.entry("incINT", "int"),
                Map.entry("incLUK", "luk"), Map.entry("incMHP", "hp"), Map.entry("incMMP", "mp"),
                Map.entry("incPAD", "watk"), Map.entry("incMAD", "matk"), Map.entry("incPDD", "wdef"),
                Map.entry("incMDD", "mdef"), Map.entry("incACC", "acc"), Map.entry("incEVA", "avoid"),
                Map.entry("incCraft", "hands"), Map.entry("incSpeed", "speed"), Map.entry("incJump", "jump"));
        names.forEach((wz, stat) -> info.getInt(wz).ifPresent(value -> data.putStat(stat, value)));
        items.put(id, data);
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
