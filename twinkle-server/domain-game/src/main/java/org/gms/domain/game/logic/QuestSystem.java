package org.gms.domain.game.logic;

import org.gms.domain.game.quest.QuestChange;
import org.gms.domain.game.spi.ProgressionState;
import org.gms.domain.game.spi.CharacterState;

/** QuestSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface QuestSystem {
    public boolean apply(ProgressionState state, QuestChange change);

    public boolean startQuest(CharacterState state, int questId);

    public boolean completeQuest(CharacterState state, int questId);

    public boolean setProgress(CharacterState state, int questId, int key, int value);

    public boolean isStarted(CharacterState state, int questId);

    public boolean isCompleted(CharacterState state, int questId);
}
