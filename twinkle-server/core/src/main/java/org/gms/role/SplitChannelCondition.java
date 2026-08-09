package org.gms.role;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * split 档频道角色装配条件（架构 4.1：进程边界是配置）。
 *
 * <p>仅当 {@code twinkle.role=channel}（频道进程）时匹配——split 档才装配网络层。
 * 与 {@link ChannelProcessCondition} 不同：single/standalone 档（role 缺省）不匹配，
 * 因为单进程不启用内部通信（CoordinatorConfig 提供进程内真值）。
 */
public final class SplitChannelCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return "channel".equals(context.getProperty("twinkle.role", String.class).orElse(null));
    }
}
