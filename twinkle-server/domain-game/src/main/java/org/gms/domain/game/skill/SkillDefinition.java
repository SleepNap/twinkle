package org.gms.domain.game.skill;

import java.util.Map;

/** 技能学习所需的 WZ 事实投影。 */
public record SkillDefinition(int skillId, int maxLevel, int requiredLevel, Map<Integer, Integer> prerequisites) {
    public SkillDefinition { prerequisites = Map.copyOf(prerequisites); }
}
