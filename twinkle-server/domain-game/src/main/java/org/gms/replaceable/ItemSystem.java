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
