package org.gms.plugin.runtime;

import org.gms.i18n.I18n;
import org.gms.plugin.ContributionHandle;

import java.util.function.Consumer;

/**
 * 命令式贡献点路由（宿主侧实现，把插件 {@code contributions().register/subscribe} 落进各注册表）。
 *
 * <p>接口在 core（插件运行时可见），实现放装配层（bootstrap 的 TwinklePluginHost，宿主看见各注册表）。
 * 这避免了插件运行时（core）对具体注册表（HandlerRegistry 等）的依赖——注册表在 net-packet/channel，
 * core 不应反向依赖。
 */
public interface ContributionRouter {

    /**
     * 注册一个贡献点对象（类型由 {@code contributionType} 标识）。
     *
     * @param contributionType 贡献点类型 code（如 {@code tick-handler}）
     * @param contribution     贡献点实例（宿主按类型 cast）
     * @param version          贡献点版本
     * @return 回滚句柄（卸载时 close）
     */
    <T> ContributionHandle register(String contributionType, T contribution, int version);

    /**
     * 订阅事件总线目标（事件监听贡献点的命令式入口）。
     *
     * @return 退订句柄（卸载时 close）
     */
    default <T> ContributionHandle subscribe(String target, Class<T> eventType, Consumer<T> consumer) {
        throw new UnsupportedOperationException(I18n.message("error.plugin.subscribe_unwired"));
    }
}
