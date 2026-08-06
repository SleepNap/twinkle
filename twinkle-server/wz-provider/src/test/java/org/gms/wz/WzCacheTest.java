package org.gms.wz;

import org.gms.domain.game.item.ItemData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WZ 磁盘缓存：首次解析写缓存，二次读缓存（loader 不再调用）。
 */
class WzCacheTest {

    @Test
    @DisplayName("缓存命中：loader 只执行一次")
    void cachesAndHitsOnSecondLoad(@TempDir Path root) {
        WzCache cache = new WzCache(root.resolve("cache"));
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<Integer, ItemData>> loader = () -> {
            calls.incrementAndGet();
            Map<Integer, ItemData> m = new HashMap<>();
            m.put(1, new ItemData(1));
            return m;
        };

        Map<Integer, ItemData> first = cache.items(loader);
        assertThat(first).containsKey(1);
        assertThat(calls).hasValue(1);

        Map<Integer, ItemData> second = cache.items(loader);
        assertThat(calls).hasValue(1);              // 二次命中缓存，loader 未再调
        assertThat(second.get(1).getItemId()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear 后重新解析")
    void clearForcesReparse(@TempDir Path root) {
        WzCache cache = new WzCache(root.resolve("cache"));
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<Integer, ItemData>> loader = () -> {
            calls.incrementAndGet();
            return new HashMap<>();
        };

        cache.items(loader);
        cache.clear();
        cache.items(loader);
        assertThat(calls).hasValue(2);
    }
}
