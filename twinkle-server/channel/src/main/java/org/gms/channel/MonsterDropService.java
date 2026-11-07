package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;

import java.util.Random;

/**
 * 击杀掉落：按 {@link MonsterDropCatalog} 生成金币/物品落物，归属给击杀者。
 *
 * <p>归属窗口内只有击杀者可拾取；窗口结束后同图玩家均可拾取。这里只决定“掉什么”，
 * 真正的落物创建、拾取和过期仍由 {@link GroundDropService} 统一串行处理。
 */
public final class MonsterDropService {

    public static final long OWNER_WINDOW_MILLIS = 5_000;

    private final GroundDropService drops;
    private final MonsterDropCatalog catalog;
    private final Random random;

    public MonsterDropService(GroundDropService drops, MonsterDropCatalog catalog) {
        this(drops, catalog, new Random());
    }

    public MonsterDropService(GroundDropService drops, MonsterDropCatalog catalog, Random random) {
        this.drops = drops;
        this.catalog = catalog;
        this.random = random;
    }

    /** 怪物死亡后调用；未配置掉落表时不产生落物。 */
    public void onKill(PlayerCharacter killer, MapleMonster monster) {
        if (killer == null || monster == null || monster.getData() == null) {
            return;
        }
        MapleMap map = killer.getMapObject();
        if (map == null) {
            return;
        }
        MonsterDropCatalog.Table table = catalog.table(monster.getData().getMobId());
        if (table == null) {
            return;
        }
        if (table.meso() != null) {
            int amount = randomBetween(table.meso().min(), table.meso().max());
            drops.dropMonsterMesos(map, killer.getId(), amount, monster.getX(), monster.getY(),
                    OWNER_WINDOW_MILLIS);
        }
        for (MonsterDropCatalog.ItemDrop item : table.items()) {
            if (random.nextInt(100) < item.chance()) {
                int quantity = randomBetween(item.min(), item.max());
                drops.dropMonsterItem(map, killer.getId(), item.itemId(), quantity,
                        monster.getX(), monster.getY(), OWNER_WINDOW_MILLIS);
            }
        }
    }

    private int randomBetween(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
