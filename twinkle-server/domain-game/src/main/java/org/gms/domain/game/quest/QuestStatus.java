package org.gms.domain.game.quest;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 任务状态（稳定层，纯数据）。角色内存态的任务进度，v83 任务语义：
 * NOT_STARTED → STARTED（进行中）→ COMPLETED（已完成）。
 * 进度 key→计数（如杀怪 100001 杀 3 只）。任务逻辑在可替换层 {@code QuestSystem}。
 */
@Getter
@Setter
public class QuestStatus {

    /** 任务状态。 */
    public enum State { NOT_STARTED, STARTED, COMPLETED }

    private final int questId;
    private State state = State.NOT_STARTED;

    @Getter(AccessLevel.NONE)
    private final Map<Integer, Integer> progress = new HashMap<>();

    public QuestStatus(int questId) {
        this.questId = questId;
    }

    public void setProgress(int key, int value) {
        progress.put(key, value);
    }

    public int getProgress(int key) {
        return progress.getOrDefault(key, 0);
    }

    /** 全部进度（不可变视图）。 */
    public Map<Integer, Integer> progress() {
        return Map.copyOf(progress);
    }
}
