package org.gms.config;

import java.util.Optional;

/**
 * 配置门面（架构 5.2 L1 / 4.6.5 配置中心）。
 *
 * <p>面向业务代码的统一入口。屏蔽底层存储：
 * <ul>
 *   <li>单进程：直接读 DB（{@code param_conf} 表）。</li>
 *   <li>分进程：coordinator 持有版本号 + 长连接推送（架构 4.6.5），但接口面零变化。</li>
 * </ul>
 *
 * <h2>热更新契约</h2>
 * <ol>
 *   <li>业务方在对象构造 / 缓存失效时机调用 {@link #get(String, Class)} 拉真值，不持有"配置快照"。</li>
 *   <li>外部写 DB → 版本号 +1 → 通过 EventBus 广播 {@link ConfigChangeEvent}。</li>
 *   <li>订阅者收到事件 → 触发自身缓存失效 → 下次 {@code get} 出新值。</li>
 * </ol>
 *
 * <p>业务代码应当在 minimal unit（按业务实体）粒度订阅，<b>不</b>持有"上次拉到的值"作为字段，避免
 * 热更新时漏改。
 */
public interface ConfigFacade {

    /**
     * 当前配置版本号。{@code 0} = 初始。
     */
    long currentVersion();

    /**
     * 读取配置项。值不存在时返回 {@link Optional#empty()}。
     *
     * <p>实现方式：内存 Index（DB 启动时一次性加载）→ O(1) 读取。可替换层调用此方法不得假设实现细节。
     *
     * @param key 配置键（DB 真值，建议 dot 命名空间，如 {@code game.level.rate}）
     * @param type 目标类型（String / Long / Integer / Boolean / Double 走默认转换）
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * 读取配置项；不存在时返回默认值。
     */
    default <T> T getOrDefault(String key, T defaultValue) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) defaultValue.getClass();
        return get(key, type).orElse(defaultValue);
    }

    /**
     * 触发配置变更广播（外部写入 DB 后调用）。
     *
     * <p>实现：版本号 +1 → 写 event bus。订阅者按需重读。
     */
    void signalChange();
}
