package org.gms.role;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * 管理进程装配条件（架构 4.1：进程边界是配置）。
 *
 * <p>满足以下任一即装配管理侧组件（coordinator / login / admin / http / ai）：
 * <ul>
 *   <li>{@code twinkle.profile} 为 {@code single}/{@code standalone}（单进程全内嵌，管理侧也在）；</li>
 *   <li>split 档且 {@code twinkle.role=coordinator}（管理进程，含 coordinator 共享状态真值）。</li>
 * </ul>
 * 频道进程（{@code twinkle.role=channel}）不装配管理侧组件（依赖互斥防 NonUniqueBeanException）。
 */
public final class ManagementProcessCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        // single/standalone 档（无 role）→ 全内嵌，管理侧装配
        String role = context.getProperty("twinkle.role", String.class).orElse(null);
        if (role == null) {
            return true;
        }
        return "coordinator".equals(role);
    }
}
