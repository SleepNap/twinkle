package org.gms.domain.game.skill;

/**
 * 技能条目（纯数据，record 不可变）。技能 id → 等级 + 过期时间。
 */
public record SkillEntry(int skillId, int level, long expiration) {

    /** 不过期技能。 */
    public SkillEntry(int skillId, int level) {
        this(skillId, level, 0L);
    }
}
