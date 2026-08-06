package org.gms.replaceable;

import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.hotreload.versioned.Versioned;

/**
 * 生命恢复系统（可替换层示例，架构第三节状态/逻辑分离 + 红线 11/12）。
 *
 * <p>演示可替换层的三条纪律：
 * <ol>
 *   <li><b>经接口访问稳定层</b>：只依赖 {@link CharacterState}（spi 接口），不碰
 *       {@code org.gms.domain.game.Character} 具体类（红线 11 / ArchUnit 规则 3 强制，防 CCE）。</li>
 *   <li><b>逻辑无状态化</b>：无字段（除注入的 versionGate 依赖），不持有跨操作状态
 *       （红线 12，热重载安全前提）。"从接口读 → 计算 → 经接口写回"。</li>
 *   <li><b>写前过版本门</b>：{@link VersionGate#decide(Versioned)} 判定逻辑版本，
 *       重载换代后旧逻辑的迟到写被拒（STALE）——架构 5.3 版本门契约在 M2 逻辑的落地。</li>
 * </ol>
 */
public final class HealthRecoverySystem {

    /** 每 tick 恢复比例（1/50 = maxHp 的 2%）。 */
    private static final int RECOVERY_DIVISOR = 50;

    private final VersionGate versionGate;

    public HealthRecoverySystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /**
     * 每 tick 恢复 HP：{@code ceil(maxHp / 50)}，封顶 maxHp。战斗/中毒等会覆盖此逻辑的
     * 机制后续以独立 system 叠加。
     *
     * @param state 目标角色状态（经 spi 接口）
     * @return 是否放行写回（迟到写 STALE 拒绝时为 false）
     */
    public boolean tick(CharacterState state) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        int maxHp = state.getMaxHp();
        int hp = state.getHp();
        if (hp >= maxHp) {
            return true;
        }
        int recovery = Math.max(1, (int) Math.ceil(maxHp / (double) RECOVERY_DIVISOR));
        state.setHp(Math.min(maxHp, hp + recovery));
        return true;
    }
}
