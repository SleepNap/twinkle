package org.gms.config;

import java.time.Instant;

/**
 * 配置变更事件（架构 5.2 L1：配置服务 + 版本号 + EventBus 广播）。
 *
 * <p>参数：配置版本号（单调递增）+ 变更时间戳。任何订阅者收到此事件后走局部缓存失效流程，
 * 重新 {@link ConfigFacade#get(String, Class)} 拉真值。
 *
 * <p>版本号语义：{@code 0} = 初始（启动时）；{@code > 0} 表示经历过至少一次外部写入（DB 写或
 * 管理员通过 control panel 改）。订阅者只需关心"是否变化"，不需关心版本号本身。
 */
public record ConfigChangeEvent(long version, Instant changedAt) {
    public static final long VERSION_INITIAL = 0L;

    public boolean isInitial() {
        return version == VERSION_INITIAL;
    }
}
