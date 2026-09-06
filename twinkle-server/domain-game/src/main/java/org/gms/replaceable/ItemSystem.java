package org.gms.replaceable;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>背包业务判定（给不给/扣不扣）在可替换层，数据结构操作（槽位/堆叠）经
 * {@link CharacterState} 接口完成——本类不引用 Inventory/Item 具体类（ArchUnit
 * 规则 3 强制）。物品静态数据 {@link ItemData} 属稳定层数据投影，可读。
 *
 * <p>写前过版本门（{@link VersionGate#decide}）：热重载换代后旧逻辑的迟到写被拒。
 */
public final class ItemSystem {

    public enum ShopResult { SUCCESS, INVALID, NO_MONEY, NO_SPACE }

    /** 普通恢复药：复验客户端指定槽位，在同一角色锁内扣物品并恢复生命/魔力。 */
    public boolean consumeRecovery(CharacterState state, short slot, int itemId, long now) {
        synchronized (state) {
            if (versionGate.decide(state) != VersionDecision.ALLOW || state.getHp() <= 0) return false;
            TradeItemSnapshot item = state.snapshotTradeItem((byte) 2, slot, 1);
            ItemData data = gameData.item(itemId);
            if (item == null || item.itemId() != itemId || data == null
                    || item.expiration() > 0 && item.expiration() <= now) return false;
            long hp = healing(data, "hp", "hpr", state.getMaxHp());
            long mp = healing(data, "mp", "mpr", state.getMaxMp());
            if (hp == 0 && mp == 0 || !state.removeTradeItems(List.of(item))) return false;
            state.setHp((int) Math.min(state.getMaxHp(), state.getHp() + hp));
            state.setMp((int) Math.min(state.getMaxMp(), state.getMp() + mp));
            state.markDirty();
            return true;
        }
    }

    private static long healing(ItemData data, String fixed, String percent, int maximum) {
        Integer flat = data.getStat(fixed), ratio = data.getStat(percent);
        return Math.max(0, flat == null ? 0 : flat)
                + (long) maximum * Math.max(0, ratio == null ? 0 : ratio) / 100;
    }

    public ShopResult buy(CharacterState state, int itemId, int quantity, int unitPrice) {
        synchronized (state) {
            if (versionGate.decide(state) != VersionDecision.ALLOW || quantity <= 0
                    || quantity > Short.MAX_VALUE || unitPrice <= 0 || gameData.item(itemId) == null)
                return ShopResult.INVALID;
            long cost = (long) quantity * unitPrice;
            if (cost > state.getMeso()) return ShopResult.NO_MONEY;
            if (!giveItem(state, itemId, quantity)) return ShopResult.NO_SPACE;
            state.setMeso(state.getMeso() - (int) cost);
            return ShopResult.SUCCESS;
        }
    }

    public ShopResult sell(CharacterState state, byte inventoryType, short slot, int itemId, int quantity) {
        synchronized (state) {
            TradeItemSnapshot item = snapshotTradeItem(state, inventoryType, slot, quantity);
            ItemData data = gameData.item(itemId);
            if (item == null || item.itemId() != itemId || data == null || data.isTradeBlock()
                    || data.getPrice() < 0 || item.cashId() != 0 || item.petId() != 0
                    || item.flag() != 0 || inventoryType == 5) return ShopResult.INVALID;
            long proceeds = (long) data.getPrice() * quantity;
            if (proceeds + state.getMeso() > Integer.MAX_VALUE) return ShopResult.INVALID;
            if (!takeTradeItems(state, List.of(item))) return ShopResult.INVALID;
            state.setMeso((int) (state.getMeso() + proceeds));
            return ShopResult.SUCCESS;
        }
    }

    /** 单槽堆叠默认上限（v83 无 slotMax 数据的物品）。 */
    public static final int DEFAULT_SLOT_MAX = 100;

    private final VersionGate versionGate;
    private final GameDataProvider gameData;

    public ItemSystem(VersionGate versionGate, GameDataProvider gameData) {
        this.versionGate = versionGate;
        this.gameData = gameData;
    }

    /**
     * 给物品（自动堆叠/分配空槽）。
     *
     * @return 空间不足或版本门拒绝时 false
     */
    public boolean giveItem(CharacterState state, int itemId, int quantity) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        if (quantity <= 0) {
            return false;
        }
        ItemData data = gameData.item(itemId);
        int slotMax = data != null && data.getSlotMax() > 0 ? data.getSlotMax() : DEFAULT_SLOT_MAX;
        return state.addItem(itemId, quantity, slotMax);
    }

    /**
     * 扣物品（跨背包类型）。
     *
     * @return 持有不足或版本门拒绝时 false（不动）
     */
    public boolean takeItem(CharacterState state, int itemId, int quantity) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        if (quantity <= 0) {
            return false;
        }
        if (state.getItemCount(itemId) < quantity) {
            return false;
        }
        return state.removeItem(itemId, quantity);
    }

    /** 批量给予前的整体容量预检，不修改角色状态。 */
    public boolean canGiveItems(CharacterState state, Map<Integer, Integer> quantities) {
        if (quantities.isEmpty()) {
            return true;
        }
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        Map<Integer, Integer> slotMaxByItem = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() <= 0) {
                return false;
            }
            ItemData data = gameData.item(entry.getKey());
            int slotMax = data != null && data.getSlotMax() > 0 ? data.getSlotMax() : DEFAULT_SLOT_MAX;
            slotMaxByItem.put(entry.getKey(), slotMax);
        }
        return state.canAddItems(quantities, slotMaxByItem);
    }

    /** 持有数量（跨背包类型合计）。 */
    public int countItem(CharacterState state, int itemId) {
        return state.getItemCount(itemId);
    }

    public boolean moveItem(CharacterState state, byte type, short source, short target, int quantity) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) return false;
        synchronized (state) {
            TradeItemSnapshot snapshot = state.snapshotTradeItem(type, source, quantity);
            if (snapshot == null) return false;
            ItemData data = gameData.item(snapshot.itemId());
            if (data == null) return false;
            int slotMax = snapshot.equip() != null ? 1 : Math.min(Short.MAX_VALUE, data.getSlotMax());
            return state.moveInventoryItem(type, source, target, quantity, slotMax);
        }
    }

    /** 读取指定背包槽位的精确交易快照。 */
    public TradeItemSnapshot snapshotTradeItem(CharacterState state, byte inventoryType,
                                               short sourcePosition, int quantity) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return null;
        }
        return state.snapshotTradeItem(inventoryType, sourcePosition, quantity);
    }

    /** 复验出价物品并模拟本方先移出、再接收后的背包容量。 */
    public boolean canExchangeTradeItems(CharacterState state,
                                         List<TradeItemSnapshot> outgoing,
                                         List<TradeItemSnapshot> incoming) {
        if (versionGate.decide(state) != VersionDecision.ALLOW || !state.hasTradeItems(outgoing)) {
            return false;
        }
        return state.canExchangeTradeItems(outgoing, incoming, slotMaxByItem(incoming));
    }

    /** 精确移出交易物品；保留按原槽位复验语义。 */
    public boolean takeTradeItems(CharacterState state, List<TradeItemSnapshot> items) {
        return versionGate.decide(state) == VersionDecision.ALLOW && state.removeTradeItems(items);
    }

    /** 按完整实例快照接收交易物品。 */
    public boolean giveTradeItems(CharacterState state, List<TradeItemSnapshot> items) {
        return versionGate.decide(state) == VersionDecision.ALLOW
                && state.addTradeItems(items, slotMaxByItem(items));
    }

    private Map<Integer, Integer> slotMaxByItem(List<TradeItemSnapshot> items) {
        Map<Integer, Integer> result = new HashMap<>();
        for (TradeItemSnapshot item : items) {
            ItemData data = gameData.item(item.itemId());
            int slotMax = item.equip() != null ? 1
                    : data != null && data.getSlotMax() > 0
                    ? data.getSlotMax() : DEFAULT_SLOT_MAX;
            result.put(item.itemId(), slotMax);
        }
        return result;
    }
}
