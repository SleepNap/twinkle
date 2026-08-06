package org.gms.replaceable;

import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 任务系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>任务状态经 {@link CharacterState} 接口读写（quest 包 QuestStatus 只读查询），
 * 业务判定（能否开始/完成）在此。写前过版本门。
 */
public final class QuestSystem {

    private final VersionGate versionGate;

    public QuestSystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /** 开始任务（已完成不能重开）。 */
    public boolean startQuest(CharacterState state, int questId) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        QuestStatus qs = state.getQuestStatus(questId);
        if (qs != null && qs.getState() == QuestStatus.State.COMPLETED) {
            return false;
        }
        return state.startQuest(questId);
    }

    /** 完成任务（仅 STARTED 可完成）。 */
    public boolean completeQuest(CharacterState state, int questId) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        QuestStatus qs = state.getQuestStatus(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        return state.completeQuest(questId);
    }

    /** 记录任务进度（仅 STARTED 可写）。 */
    public boolean setProgress(CharacterState state, int questId, int key, int value) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        return state.setQuestProgress(questId, key, value);
    }

    public boolean isStarted(CharacterState state, int questId) {
        QuestStatus qs = state.getQuestStatus(questId);
        return qs != null && qs.getState() == QuestStatus.State.STARTED;
    }

    public boolean isCompleted(CharacterState state, int questId) {
        QuestStatus qs = state.getQuestStatus(questId);
        return qs != null && qs.getState() == QuestStatus.State.COMPLETED;
    }
}
