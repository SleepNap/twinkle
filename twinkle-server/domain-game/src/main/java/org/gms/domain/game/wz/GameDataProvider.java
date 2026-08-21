package org.gms.domain.game.wz;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;

import java.util.Map;

/**
 * 游戏逻辑读取 WZ 静态数据的稳定契约。
 *
 * <p>调用方只按 id 查询，不持有某一代数据 Map；底层 WZ 快照换代后，后续查询立即读取新版本。
 */
public interface GameDataProvider {

    ItemData item(int itemId);

    MobData mob(int mobId);

    long version();

    /** 小型固定数据源，供独立逻辑装配和测试使用。 */
    static GameDataProvider fixed(Map<Integer, ItemData> items, Map<Integer, MobData> mobs) {
        Map<Integer, ItemData> itemSnapshot = Map.copyOf(items);
        Map<Integer, MobData> mobSnapshot = Map.copyOf(mobs);
        return new GameDataProvider() {
            @Override
            public ItemData item(int itemId) {
                return itemSnapshot.get(itemId);
            }

            @Override
            public MobData mob(int mobId) {
                return mobSnapshot.get(mobId);
            }

            @Override
            public long version() {
                return 1;
            }
        };
    }
}
