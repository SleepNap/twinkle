package org.gms.wz;

import org.gms.domain.game.mob.MobData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 怪物数据加载：Mob.wz → MobData（info 字段解析）。
 * 用内嵌测试资源（src/test/resources/wz/），不依赖外部北斗目录。
 */
class MobLoaderTest {

    private static final Path WZ_ROOT = resourceRoot();

    private static Path resourceRoot() {
        try {
            return Path.of(Objects.requireNonNull(
                    MobLoaderTest.class.getResource("/wz")).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("测试资源定位失败", e);
        }
    }

    @Test
    @DisplayName("解析怪物属性：等级/HP/EXP/攻防/标志")
    void loadsMobInfo() {
        Map<Integer, MobData> mobs = new MobLoader(WZ_ROOT).loadAll();

        assertThat(mobs).hasSize(1);
        MobData snail = mobs.get(100100);
        assertThat(snail).isNotNull();
        assertThat(snail.getLevel()).isEqualTo(1);
        assertThat(snail.getMaxHp()).isEqualTo(8);
        assertThat(snail.getExp()).isEqualTo(3);
        assertThat(snail.getPad()).isEqualTo(12);
        assertThat(snail.getPdd()).isZero();
        assertThat(snail.getAcc()).isEqualTo(20);
        assertThat(snail.getSpeed()).isEqualTo(-65);
        assertThat(snail.isBodyAttack()).isTrue();
        assertThat(snail.isBoss()).isFalse();
    }
}
