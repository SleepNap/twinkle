package org.gms.replaceable;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

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
    private final Map<Integer, ItemData> itemData;

    public ItemSystem(VersionGate versionGate, Map<Integer, ItemData> itemData) {
        this.versionGate = versionGate;
        this.itemData = itemData;
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
        ItemData data = itemData.get(itemId);
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

    /** 持有数量（跨背包类型合计）。 */
    public int countItem(CharacterState state, int itemId) {
        return state.getItemCount(itemId);
    }
}
