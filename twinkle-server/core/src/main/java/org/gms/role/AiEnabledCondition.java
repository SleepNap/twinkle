package org.gms.role;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * AI 模块启用条件（可选功能，2C2G 红线：默认不装配）。
 *
 * <p>AI 是管理侧可选功能，与 WAL 同类的"按需启用"（架构 9.2 逐组件 2C2G 判定）：
 * 2C2G 默认不装配（省内存/CPU），需要时经 {@code twinkle.ai.enabled=true} 显式开启。
 *
 * <p>满足以下**全部**才装配：
 * <ul>
 *   <li>管理进程（single/standalone 全内嵌 或 split 的 coordinator 角色）——复用
 *       {@link ManagementProcessCondition} 语义（AI 是管理侧模块）；</li>
 *   <li>{@code twinkle.ai.enabled} 为 {@code true}（默认 false）。</li>
 * </ul>
 * 频道进程（{@code twinkle.role=channel}）不装配 AI。
 */
public final class AiEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        // 1. 管理进程前提（与 ManagementProcessCondition 一致：无 role=single/standalone，或 role=coordinator）
        String role = context.getProperty("twinkle.role", String.class).orElse(null);
        boolean managementProcess = role == null || "coordinator".equals(role);
        if (!managementProcess) {
            return false;
        }
        // 2. 可选开关：默认关，twinkle.ai.enabled=true 开
        return context.getProperty("twinkle.ai.enabled", Boolean.class, false);
    }
}
