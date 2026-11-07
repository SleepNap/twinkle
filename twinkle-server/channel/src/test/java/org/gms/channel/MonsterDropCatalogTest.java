package org.gms.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 掉落配置格式、概率与边界校验。 */
public class MonsterDropCatalogTest {

    @Test public void parsesMesoAndItemDrops(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("drops.properties"),
                "100100=meso:10:30,2000000:50:1:2\n100101=2000001:100:3:3\n");
        var catalog = new MonsterDropCatalog(file.toString());

        var first = catalog.table(100100);
        assertThat(first.meso().min()).isEqualTo(10);
        assertThat(first.meso().max()).isEqualTo(30);
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().itemId()).isEqualTo(2000000);
        assertThat(first.items().getFirst().chance()).isEqualTo(50);
        assertThat(first.items().getFirst().min()).isOne();
        assertThat(first.items().getFirst().max()).isEqualTo(2);

        var second = catalog.table(100101);
        assertThat(second.meso()).isNull();
        assertThat(second.items()).hasSize(1);
        assertThat(catalog.table(999999)).isNull();
    }

    @Test public void rejectsInvalidChanceRangeAndMeso(@TempDir Path root) throws Exception {
        Path badChance = Files.writeString(root.resolve("bad-chance.properties"),
                "100100=2000000:0:1:1\n");
        assertThatThrownBy(() -> new MonsterDropCatalog(badChance.toString()))
                .isInstanceOf(IllegalArgumentException.class);

        Path badRange = Files.writeString(root.resolve("bad-range.properties"),
                "100100=meso:30:10\n");
        assertThatThrownBy(() -> new MonsterDropCatalog(badRange.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
