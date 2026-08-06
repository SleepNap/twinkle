package org.gms.wz;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MobData;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 WZ 数据手动验证（架构 6.4：twinkle.wz.path 直接指定 WZ 目录）。
 *
 * <p>默认 {@code @Disabled}：需要本机有真实 WZ 解包数据（如北斗 {@code gms-server/wz}）。
 * 手动验证时把 {@link #WZ_ROOT} 改为本机 WZ 根并去掉 {@code @Disabled}。
 * 2026-08-06 已验证通过：新手村 100000000（town/portals）、野图 100000004（24 怪物刷新点）。
 */
@Disabled("手动验证：需真实 WZ 数据路径")
class RealWzManualTest {

    /** 手动指向：北斗 WZ 根（或部署机 WZ 根）。 */
    private static final Path WZ_ROOT = Path.of("E:/LocalGit/GitHub/BeiDou-Server/gms-server/wz");

    @Test
    @DisplayName("加载真实地图：新手村(town) + 野图(怪物刷新点)")
    void loadRealMaps() {
        MapLoader loader = new MapLoader(WZ_ROOT);

        // 新手村 100000000：town=1，只有 NPC 无怪物
        MapleMap town = loader.load(100000000).orElseThrow();
        System.out.println("新手村 " + town.getMapId()
                + " town=" + town.isTown()
                + " portals=" + town.portals().size()
                + " spawnPoints=" + town.spawnPoints().size()
                + " footholds=" + town.footholds().size()
                + " onUserEnter=" + town.getOnUserEnter());
        assertThat(town.isTown()).isTrue();
        assertThat(town.portals()).isNotEmpty();
        assertThat(town.spawnPoints()).isEmpty(); // 安全区无怪，符合数据
        assertThat(town.footholds()).hasSizeGreaterThan(30); // 真实数据 38 条地面线段

        // 野图 100000004：24 个怪物刷新点
        MapleMap field = loader.load(100000004).orElseThrow();
        System.out.println("野图 " + field.getMapId()
                + " town=" + field.isTown()
                + " portals=" + field.portals().size()
                + " spawnPoints=" + field.spawnPoints().size()
                + " footholds=" + field.footholds().size());
        assertThat(field.isTown()).isFalse();
        assertThat(field.spawnPoints()).isNotEmpty();
        assertThat(field.spawnPoints()).hasSize(24); // 真实数据 24 个怪物刷新点
        // mobTime 真实多为 0（即时/默认重生），重生间隔语义属重生 system 处理，此处仅原样存值
        assertThat(field.footholds()).isNotEmpty();
    }

    @Test
    @DisplayName("真实 Item.wz / Mob.wz 数据")
    void loadRealItemsAndMobs() {
        ItemLoader itemLoader = new ItemLoader(WZ_ROOT);
        Map<Integer, ItemData> items = itemLoader.loadAll();
        System.out.println("Item.wz 物品数=" + items.size());
        assertThat(items).isNotEmpty();
        ItemData red = items.get(2_000_000);      // 红药水
        assertThat(red).isNotNull();
        System.out.println("红药水 2000000: price=" + red.getPrice() + " hp=" + red.getStat("hp"));

        MobLoader mobLoader = new MobLoader(WZ_ROOT);
        Map<Integer, MobData> mobs = mobLoader.loadAll();
        System.out.println("Mob.wz 怪物数=" + mobs.size());
        assertThat(mobs).isNotEmpty();
        MobData snail = mobs.get(100_100);         // 蜗牛
        assertThat(snail).isNotNull();
        System.out.println("蜗牛 100100: level=" + snail.getLevel() + " maxHP=" + snail.getMaxHp()
                + " exp=" + snail.getExp());
        assertThat(snail.getMaxHp()).isEqualTo(8);
    }
}
