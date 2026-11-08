package org.gms.logic.game;

import org.gms.domain.game.logic.*;

import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.item.ScrollData;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.EquipmentState;
import org.gms.domain.game.spi.EquipmentState.SlotMove;
import org.gms.domain.game.spi.EquipmentStats;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.spi.TradeItemSnapshot.EquipSnapshot;
import org.gms.domain.game.wz.GameDataProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/** 装备穿脱与汇总由本项目独立实现；仅核对 WZ 字段、v83 负槽位和互斥部位的含义。 */
public final class DefaultEquipmentSystem implements EquipmentSystem {

    /** v83 白色卷轴：失败时保留升级次数，按客户端勾选消耗。 */
    private static final int WHITE_SCROLL_ITEM_ID = 2_340_000;

    private final Predicate<CharacterState> accepts;
    private final GameDataProvider data;

    public DefaultEquipmentSystem(Predicate<CharacterState> accepts, GameDataProvider data) {
        this.accepts = accepts;
        this.data = data;
    }

    public long dataVersion() { return data.version(); }

    /** 装备请求数量不是拆分数；v83 客户端可能填 0，实际总是搬动一件完整装备。 */
    public Change move(EquipmentState state, short source, short target, int quantity, long now) {
        synchronized (state) {
            if (!accepts.test(state) || state.getHp() <= 0 || quantity < 0 || quantity > 1
                    || source == 0 || target == 0 || source == target || source > 0 && target > 0
                    || source > state.equipmentSlotLimit() || target > state.equipmentSlotLimit()) return null;
            Map<Short, TradeItemSnapshot> before = state.equipmentItems();
            long dataVersion = data.version();
            TradeItemSnapshot item = before.get(source);
            if (!isEquipment(item)) return null;
            Map<Short, TradeItemSnapshot> planned = new HashMap<>(before);
            List<SlotMove> moves = new ArrayList<>();
            Set<Short> bound = Set.of();
            if (source < 0 && target > 0) {
                if (planned.containsKey(target)) return null;
                planMove(planned, moves, source, target);
            } else {
                ItemData definition = data.item(item.itemId());
                if (source < 0 || !usable(item, now) || !allowed(item, target, definition)) return null;
                TradeItemSnapshot previous = planned.get(target);
                if (previous != null && !isEquipment(previous)) return null;
                planMove(planned, moves, source, target);
                short conflict = conflictingSlot(item, definition.getEquipment(), target, planned);
                if (conflict != 0 && planned.containsKey(conflict)) {
                    if (!isEquipment(planned.get(conflict))) return null;
                    short free = freeSlot(planned, state.equipmentSlotLimit());
                    if (free == 0) return null;
                    planMove(planned, moves, conflict, free);
                }
                EquipmentData equipment = definition.getEquipment();
                EquipmentStats available = sum(planned, target, now);
                if (!requirements(state, definition, available)) return null;
                if (equipment.uniqueEquipped() && planned.entrySet().stream()
                        .filter(entry -> entry.getKey() < 0 && entry.getValue().itemId() == item.itemId()).count() > 1) return null;
                if (equipment.bindOnEquip() && (item.flag() & 8) == 0) bound = Set.of(target);
            }
            if (data.version() != dataVersion || !accepts.test(state) || !state.applyEquipmentMoves(before, moves, bound)) return null;
            refresh(state, now);
            return new Change(moves, bound);
        }
    }

    /**
     * v83 升级卷轴：成功加属性并提升一级，失败消耗一次升级机会，诅咒直接销毁装备。
     *
     * <p>只接普通 {@code 2040xxx-2048xxx} 卷轴；{@code 2049xxx}（混沌/纯白/饰品特殊卷轴）
     * 与匠人之魂留在后续批次。卷轴在判定前先按实例扣掉，白色卷轴按客户端勾选一并消耗。
     */
    public ScrollResult scroll(EquipmentState state, short scrollSlot, short equipSlot,
                               boolean whiteScroll, long now, RandomGenerator random) {
        synchronized (state) {
            if (!accepts.test(state) || state.getHp() <= 0 || scrollSlot <= 0 || equipSlot >= 0) return null;
            TradeItemSnapshot scroll = state.snapshotTradeItem((byte) 2, scrollSlot, 1);
            if (scroll == null || expired(scroll, now) || !isStandardScroll(scroll.itemId())) return null;
            ItemData scrollDefinition = data.item(scroll.itemId());
            if (scrollDefinition == null || scrollDefinition.getScroll() == null) return null;
            ScrollData scrollData = scrollDefinition.getScroll();
            Map<Short, TradeItemSnapshot> before = state.equipmentItems();
            TradeItemSnapshot item = before.get(equipSlot);
            if (!usable(item, now)) return null;
            ItemData definition = data.item(item.itemId());
            if (definition == null || definition.getEquipment() == null || definition.getEquipment().cash()
                    || !scrollData.allows(item.itemId())
                    || !sameScrollCategory(scroll.itemId(), item.itemId())) return null;
            EquipSnapshot stats = item.equip();
            if (stats.upgradeSlots() <= 0) return null;

            boolean usedWhiteScroll = whiteScroll && state.getItemCount(WHITE_SCROLL_ITEM_ID) > 0;
            if (!state.removeTradeItems(List.of(scroll))) return null;
            if (usedWhiteScroll && !state.removeItem(WHITE_SCROLL_ITEM_ID, 1)) {
                refund(state, scroll, false);
                return null;
            }
            boolean success = random.nextInt(100) < scrollData.success();
            boolean cursed = !success && random.nextInt(100) < scrollData.cursed();
            if (cursed) {
                if (!state.removeEquipment(before, Set.of(equipSlot))) {
                    refund(state, scroll, usedWhiteScroll);
                    return null;
                }
                return new ScrollResult(ScrollOutcome.CURSE, usedWhiteScroll);
            }
            EquipSnapshot updated = success ? improved(stats, scrollData.stats())
                    : spentSlot(stats, usedWhiteScroll);
            TradeItemSnapshot replacement = new TradeItemSnapshot(item.inventoryType(), item.sourcePosition(),
                    item.itemId(), item.quantity(), item.cashId(), item.petId(), item.owner(), item.flag(),
                    item.expiration(), item.giftFrom(), updated, null);
            if (!state.updateEquipmentItem(before, equipSlot, replacement)) {
                refund(state, scroll, usedWhiteScroll);
                return null;
            }
            return new ScrollResult(success ? ScrollOutcome.SUCCESS : ScrollOutcome.FAIL, usedWhiteScroll);
        }
    }

    /** 已穿戴实例到期后从装备栏清理，容量不足也不继续提供过期加成。 */
    public boolean expire(EquipmentState state, long now) {
        synchronized (state) {
            if (!accepts.test(state)) return false;
            Map<Short, TradeItemSnapshot> before = state.equipmentItems();
            Set<Short> expired = before.entrySet().stream().filter(entry -> entry.getKey() < 0
                    && isEquipment(entry.getValue()) && entry.getValue().expiration() >= 0
                    && entry.getValue().expiration() <= now).map(Map.Entry::getKey).collect(Collectors.toSet());
            return state.removeEquipment(before, expired);
        }
    }

    /** 重登/换装后重新生成派生属性；已过期或不支持的装备不贡献数值。 */
    public boolean refresh(EquipmentState state, long now) {
        synchronized (state) {
            if (!accepts.test(state)) return false;
            long version = data.version();
            EquipmentStats stats = sum(state.equipmentItems(), (short) 0, now);
            if (version != data.version() || !accepts.test(state)) return false;
            state.setEquipmentStats(stats);
            if (state.getHp() > state.effectiveMaxHp()) state.setHp(state.effectiveMaxHp());
            if (state.getMp() > state.effectiveMaxMp()) state.setMp(state.effectiveMaxMp());
            return true;
        }
    }

    private boolean requirements(EquipmentState state, ItemData item, EquipmentStats available) {
        EquipmentData eq = item.getEquipment();
        int family = state.getJob() % 1000 / 100;
        boolean beginner = state.getJob() == 0 || state.getJob() == 1000 || state.getJob() == 2000 || state.getJob() == 2001;
        boolean jobAllowed = eq.requiredJob() == 0 || eq.requiredJob() == -1 && beginner
                || eq.requiredJob() > 0 && family >= 1 && family <= 5 && (eq.requiredJob() & (1 << (family - 1))) != 0;
        return jobAllowed && state.getLevel() >= item.getReqLevel() && state.getFame() >= eq.requiredFame()
                && (eq.gender() > 1 || eq.gender() == state.getGender())
                && state.getStrStat() + available.str() >= eq.requiredStr()
                && state.getDexStat() + available.dex() >= eq.requiredDex()
                && state.getIntStat() + available.intelligence() >= eq.requiredInt()
                && state.getLukStat() + available.luk() >= eq.requiredLuk();
    }

    private boolean allowed(TradeItemSnapshot item, short target, ItemData definition) {
        if (definition == null || definition.getEquipment() == null || target >= 0) return false;
        EquipmentData eq = definition.getEquipment();
        if (eq.cash() && item.cashId() <= 0) return false;
        int slot = eq.cash() ? target + 100 : target;
        // WZ 部位字符串及负槽位是协议事实；未知/宠物/骑乘部位明确拒绝。
        return switch (slot) {
            case -11 -> Set.of("Wp", "WpSi", "WpSp").contains(eq.slot());
            case -12, -13, -15, -16 -> "Ri".equals(eq.slot());
            case -5 -> Set.of("Ma", "MaPn").contains(eq.slot());
            case -8 -> Set.of("GlGw", "Gv").contains(eq.slot());
            case -1 -> Set.of("Cp", "HrCp").contains(eq.slot());
            case -2 -> "Af".equals(eq.slot());
            case -3 -> "Ay".equals(eq.slot());
            case -4 -> "Ae".equals(eq.slot());
            case -6 -> "Pn".equals(eq.slot());
            case -7 -> "So".equals(eq.slot());
            case -9 -> "Sr".equals(eq.slot());
            case -10 -> "Si".equals(eq.slot());
            case -17 -> "Pe".equals(eq.slot());
            case -49 -> "Me".equals(eq.slot());
            case -50 -> "Be".equals(eq.slot());
            default -> false;
        };
    }

    private short conflictingSlot(TradeItemSnapshot item, EquipmentData eq, short target, Map<Short, TradeItemSnapshot> planned) {
        int offset = eq.cash() ? -100 : 0;
        int slot = target - offset;
        if (slot == -5 && "MaPn".equals(eq.slot())) return (short) (-6 + offset);
        if (slot == -6) {
            TradeItemSnapshot top = planned.get((short) (-5 + offset));
            ItemData topData = top == null ? null : data.item(top.itemId());
            if (topData != null && topData.getEquipment() != null && "MaPn".equals(topData.getEquipment().slot()))
                return (short) (-5 + offset);
        }
        if (!eq.cash() && slot == -11 && twoHanded(item.itemId())) return -10;
        if (!eq.cash() && slot == -10) {
            TradeItemSnapshot weapon = planned.get((short) -11);
            if (weapon != null && twoHanded(weapon.itemId())) return -11;
        }
        return 0;
    }

    /** 只接普通升级卷轴；2049xxx 的特殊卷轴与匠人之魂未接入。 */
    private static boolean isStandardScroll(int itemId) {
        return itemId / 10000 == 204 && itemId / 1000 != 2049;
    }

    /** 卷轴 204XYZ 的类别位与装备 id 的第三段对应（头盔 0、上衣 4、武器 30 等）。 */
    private static boolean sameScrollCategory(int scrollId, int equipmentId) {
        return (scrollId / 100) % 100 == (equipmentId / 10000) % 100;
    }

    private static boolean expired(TradeItemSnapshot item, long now) {
        return item.expiration() >= 0 && item.expiration() <= now;
    }

    private static EquipSnapshot improved(EquipSnapshot stats, Map<String, Integer> increments) {
        return new EquipSnapshot(
                (byte) Math.max(0, stats.upgradeSlots() - 1),
                (short) Math.clamp(stats.level() + 1, 0, 255),
                add(stats.strStat(), increments.get("str")),
                add(stats.dexStat(), increments.get("dex")),
                add(stats.intStat(), increments.get("int")),
                add(stats.lukStat(), increments.get("luk")),
                add(stats.hp(), increments.get("hp")),
                add(stats.mp(), increments.get("mp")),
                add(stats.wAtk(), increments.get("watk")),
                add(stats.mAtk(), increments.get("matk")),
                add(stats.wDef(), increments.get("wdef")),
                add(stats.mDef(), increments.get("mdef")),
                add(stats.acc(), increments.get("acc")),
                add(stats.avoid(), increments.get("avoid")),
                add(stats.hands(), increments.get("hands")),
                add(stats.speed(), increments.get("speed")),
                add(stats.jump(), increments.get("jump")),
                stats.vicious(), stats.itemLevel(), stats.itemExp(), stats.ringId());
    }

    /** 失败时消耗一次升级机会；白色卷轴生效则保留次数，其它属性不变。 */
    private static EquipSnapshot spentSlot(EquipSnapshot stats, boolean protectedByWhiteScroll) {
        byte slots = protectedByWhiteScroll ? stats.upgradeSlots() : (byte) Math.max(0, stats.upgradeSlots() - 1);
        return new EquipSnapshot(slots, stats.level(),
                stats.strStat(), stats.dexStat(), stats.intStat(), stats.lukStat(), stats.hp(), stats.mp(),
                stats.wAtk(), stats.mAtk(), stats.wDef(), stats.mDef(), stats.acc(), stats.avoid(),
                stats.hands(), stats.speed(), stats.jump(),
                stats.vicious(), stats.itemLevel(), stats.itemExp(), stats.ringId());
    }

    private static short add(short value, Integer increment) {
        long total = value + (long) (increment == null ? 0 : increment);
        return (short) Math.clamp(total, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /** 提交失败时把已扣卷轴放回；正常路径不会走到，用于保证一次砸卷要么全成要么全退。 */
    private void refund(EquipmentState state, TradeItemSnapshot scroll, boolean whiteScroll) {
        ItemData definition = data.item(scroll.itemId());
        int slotMax = definition != null && definition.getSlotMax() > 0 ? definition.getSlotMax() : 100;
        state.addTradeItems(List.of(scroll), Map.of(scroll.itemId(), slotMax));
        if (whiteScroll) state.addItem(WHITE_SCROLL_ITEM_ID, 1, 100);
    }

    private static boolean twoHanded(int id) { return id / 10000 >= 140 && id / 10000 <= 149; }
    private static boolean isEquipment(TradeItemSnapshot item) {
        return item != null && item.inventoryType() == 1 && item.itemId() / 1000000 == 1
                && item.equip() != null && item.pet() == null && item.quantity() == 1;
    }
    private static boolean usable(TradeItemSnapshot item, long now) {
        return isEquipment(item) && (item.expiration() < 0 || item.expiration() > now);
    }
    private static short freeSlot(Map<Short, TradeItemSnapshot> planned, int limit) {
        for (short slot = 1; slot <= limit; slot++) if (!planned.containsKey(slot)) return slot;
        return 0;
    }
    private static void planMove(Map<Short, TradeItemSnapshot> planned, List<SlotMove> moves, short from, short to) {
        TradeItemSnapshot item = planned.remove(from), replaced = planned.put(to, item);
        if (replaced != null) planned.put(from, replaced);
        moves.add(new SlotMove(from, to));
    }

    private EquipmentStats sum(Map<Short, TradeItemSnapshot> items, short excluded, long now) {
        int[] totals = new int[15];
        items.forEach((slot, item) -> {
            if (slot >= 0 || slot == excluded || !usable(item, now)) return;
            ItemData definition = data.item(item.itemId());
            if (!allowed(item, slot, definition) || definition.getEquipment().cash()) return;
            EquipSnapshot eq = item.equip();
            int[] stats = {eq.strStat(), eq.dexStat(), eq.intStat(), eq.lukStat(), eq.hp(), eq.mp(),
                    eq.wAtk(), eq.mAtk(), eq.wDef(), eq.mDef(), eq.acc(), eq.avoid(), eq.hands(), eq.speed(), eq.jump()};
            for (int i = 0; i < totals.length; i++) totals[i] += stats[i];
        });
        return new EquipmentStats(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6],
                totals[7], totals[8], totals[9], totals[10], totals[11], totals[12], totals[13], totals[14]);
    }

    /** 新建普通装备只使用本服 WZ 模板；已有实例换装时从不重新套用模板属性。 */
    public static TradeItemSnapshot createItem(ItemData data) {
        EquipmentData eq = data.getEquipment();
        EquipSnapshot stats = new EquipSnapshot((byte) Math.clamp(eq.upgradeSlots(), 0, 255), (short) 0,
                stat(data, "str"), stat(data, "dex"), stat(data, "int"), stat(data, "luk"),
                stat(data, "hp"), stat(data, "mp"), stat(data, "watk"), stat(data, "matk"),
                stat(data, "wdef"), stat(data, "mdef"), stat(data, "acc"), stat(data, "avoid"),
                stat(data, "hands"), stat(data, "speed"), stat(data, "jump"), (byte) 0, (byte) 0, 0, 0);
        return new TradeItemSnapshot((byte) 1, (short) 0, data.getItemId(), 1, 0, 0, null, 0, -1, null, stats, null);
    }
    private static short stat(ItemData data, String key) {
        Integer value = data.getStat(key);
        return (short) Math.clamp(value == null ? 0 : value, Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
