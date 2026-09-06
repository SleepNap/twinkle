package org.gms.domain.game.skill;

import java.util.Map;

/** WZ 当前技能等级的普通自身 Buff 投影，不含脚本、召唤和队伍范围效果。 */
public record BuffDefinition(int skillId, int level, int hpCost, int mpCost, int durationMillis,
                             int cooldownMillis, Map<Long, Integer> stats) {
    public BuffDefinition { stats = Map.copyOf(stats); }
}
