package org.gms.wz;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;

import java.util.Map;

/** 内置 WZ 资源键。业务模型尚未落地的目录先通过 {@link WzNodeCatalog} 提供通用访问。 */
public final class WzResources {

    public static final WzResourceKey<Map<Integer, ItemData>> ITEMS = new WzResourceKey<>("items");
    public static final WzResourceKey<Map<Integer, MobData>> MOBS = new WzResourceKey<>("mobs");
    public static final WzResourceKey<WzMapCatalog> MAPS = new WzResourceKey<>("maps");
    public static final WzResourceKey<WzNodeCatalog> NAMES = new WzResourceKey<>("names");
    public static final WzResourceKey<WzNodeCatalog> SKILLS = new WzResourceKey<>("skills");
    public static final WzResourceKey<WzNodeCatalog> BUFFS = new WzResourceKey<>("buffs");
    public static final WzResourceKey<WzNodeCatalog> QUESTS = new WzResourceKey<>("quests");

    private WzResources() {
    }
}
