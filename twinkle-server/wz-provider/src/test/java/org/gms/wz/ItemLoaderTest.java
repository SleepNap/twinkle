package org.gms.wz;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.item.ScrollData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物品数据加载：Item.wz → ItemData（info/spec 解析）。
 * 用内嵌测试资源（src/test/resources/wz/），不依赖外部北斗目录。
 */
class ItemLoaderTest {

    private static final Path WZ_ROOT = resourceRoot();

    private static Path resourceRoot() {
        try {
            return Path.of(Objects.requireNonNull(
                    ItemLoaderTest.class.getResource("/wz")).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("测试资源定位失败", e);
        }
    }

    @Test
    @DisplayName("解析 Consume 物品：price/slotMax + spec 效果")
    void loadsConsumeItems() {
        Map<Integer, ItemData> items = new ItemLoader(WZ_ROOT).loadAll();

        assertThat(items).hasSize(4);
        ItemData hp50 = items.get(2_000_000);       // 十进制 id（v83 物品 id 十进制）
        assertThat(hp50).isNotNull();
        assertThat(hp50.getPrice()).isEqualTo(25);
        assertThat(hp50.getSlotMax()).isEqualTo(100);
        assertThat(hp50.getStat("hp")).isEqualTo(50);

        ItemData hp150 = items.get(2_000_001);
        assertThat(hp150.getPrice()).isEqualTo(80);
        assertThat(hp150.getReqLevel()).isEqualTo(5);
        assertThat(hp150.getStat("hp")).isEqualTo(150);
        assertThat(hp150.getStat("mp")).isEqualTo(30);
    }

    @Test
    @DisplayName("解析卷轴：成功率/诅咒率、incXXX 增量与 req 限定装备")
    void loadsScrollData() {
        Map<Integer, ItemData> items = new ItemLoader(WZ_ROOT).loadAll();

        ScrollData helmet = items.get(2_040_000).getScroll();
        assertThat(helmet).isNotNull();
        assertThat(helmet.success()).isEqualTo(60);
        assertThat(helmet.cursed()).isEqualTo(50);
        assertThat(helmet.requiredItems()).isEmpty();
        assertThat(helmet.stats()).containsEntry("wdef", 2).containsEntry("str", 1);

        ScrollData restricted = items.get(2_040_023).getScroll();
        assertThat(restricted.success()).isEqualTo(100);
        assertThat(restricted.cursed()).isZero();
        assertThat(restricted.requiredItems()).containsExactly(1_002_867);
        assertThat(restricted.allows(1_002_867)).isTrue();
        assertThat(restricted.allows(1_302_000)).isFalse();
        assertThat(restricted.stats()).containsEntry("int", 1);

        assertThat(items.get(2_000_000).getScroll()).isNull();
    }
}
