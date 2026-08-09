package org.gms.role;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * 频道进程装配条件（架构 4.1：进程边界是配置）。
 *
 * <p>满足以下任一即装配频道侧组件（channel / tick / lease / persist）：
 * <ul>
 *   <li>{@code twinkle.profile} 为 {@code single}/{@code standalone}（单进程全内嵌，频道也在）；</li>
 *   <li>split 档且 {@code twinkle.role=channel}（频道进程）。</li>
 * </ul>
 * 管理进程（{@code twinkle.role=coordinator}）不装配频道侧组件（游戏世界只在一个进程）。
 */
public final class ChannelProcessCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        String role = context.getProperty("twinkle.role", String.class).orElse(null);
        if (role == null) {
            return true;
        }
        return "channel".equals(role);
    }
}
