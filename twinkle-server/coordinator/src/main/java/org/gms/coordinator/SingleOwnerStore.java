package org.gms.coordinator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.gms.i18n.I18n;

/**
 * 单一属主存储（架构 4.4 三机制之一"状态单一属主"：共享状态真值只在 coordinator 一处持有）。
 *
 * <p>频道需改共享状态 → 发消息给 coordinator（本类落真值）→ 频道来读。绝不做"每频道各持一份
 * 靠同步保持一致"（数据发散、互相覆盖的根源）。
 *
 * <p>通用值存储：key → value + 版本号（乐观并发：写者带期望版本，冲突拒绝）。M4 落地三类：
 * <ul>
 *   <li>活动公告（notice 文本，广播展示）。</li>
 *   <li>好友在线请求（buddy 请求状态，单一属主落点）。</li>
 *   <li>商店资金（counter 计数器，单一属主账本）。</li>
 * </ul>
 *
 * <p>接口不假设进程内（铁律 1）：单进程内本类直接落真值；M6 分布式换网络实现（RPC 到
 * coordinator 应用本类），接口不变。
 */
public final class SingleOwnerStore {

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 单条存储项：值 + 版本号（写者带期望版本实现乐观并发）。 */
    public record Entry(Object value, long version) {
    }

    /** 读取真值。 */
    public Optional<Entry> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    /**
     * 写入真值（带版本：调用方读到版本后修改，写入时校验仍为该版本，冲突抛异常——防覆盖）。
     *
     * @param expectedVersion 期望版本（-1 = 无条件写入）
     * @return 新版本号
     * @throws IllegalStateException 版本冲突（被并发写者覆盖）
     */
    public long put(String key, Object value, long expectedVersion) {
        AtomicLong applied = new AtomicLong(-1);
        store.compute(key, (k, existing) -> {
            long current = existing == null ? 0 : existing.version();
            if (expectedVersion >= 0 && current != expectedVersion) {
                throw new IllegalStateException(I18n.message("error.coordinator.version_conflict",
                        key, expectedVersion, current));
            }
            long next = current + 1;
            applied.set(next);
            return new Entry(value, next);
        });
        return applied.get();
    }

    /**
     * 原子自增（counter 账本：商店资金等）。返回新值。
     */
    public long increment(String key, long delta) {
        AtomicLong result = new AtomicLong();
        store.compute(key, (k, existing) -> {
            long current = existing == null ? 0 : ((Number) existing.value()).longValue();
            long next = current + delta;
            result.set(next);
            return new Entry(next, (existing == null ? 0 : existing.version()) + 1);
        });
        return result.get();
    }

    /** 删除（幂等）。 */
    public void remove(String key) {
        store.remove(key);
    }

    /** 全部快照（观测/管理用）。 */
    public Map<String, Entry> snapshot() {
        return Map.copyOf(store);
    }

    /** 项数（观测）。 */
    public int size() {
        return store.size();
    }
}
