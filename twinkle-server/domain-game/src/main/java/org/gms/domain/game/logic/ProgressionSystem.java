package org.gms.domain.game.logic;

import org.gms.domain.game.spi.ProgressionState;
import org.gms.domain.game.skill.SkillDefinition;
import org.gms.domain.game.skill.BuffDefinition;
import org.gms.domain.game.spi.BuffState;
import org.gms.domain.game.spi.CharacterState;
import java.util.Map;

/** ProgressionSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface ProgressionSystem {
    public boolean accepts(CharacterState state);

    public boolean castBuff(BuffState state, BuffDefinition effect, long now);

    public long cancelBuff(BuffState state, int skillId, long now, boolean expiryOnly);

    public boolean allocateAp(ProgressionState state, Map<Integer, Integer> increments);

    public boolean allocateSp(ProgressionState state, SkillDefinition definition);
}
