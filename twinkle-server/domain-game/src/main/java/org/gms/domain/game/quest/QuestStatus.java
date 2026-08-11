package org.gms.domain.game.quest;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final Map<Integer, String> progress = new LinkedHashMap<>();

    /** 完成时间（Unix 毫秒）；未记录为 0。 */
    private long completionTime;
    private long expirationTime;
    private int forfeited;
    private int completed;
    /** WZ infoNumber 对应的附加任务记录；无则为 0。 */
    private int infoNumber;

    public QuestStatus(int questId) {
        this.questId = questId;
    }

    public void setProgress(int key, int value) {
        progress.put(key, Integer.toString(value));
    }

    public void setProgressText(int key, String value) {
        progress.put(key, value == null ? "" : value);
    }

    public int getProgress(int key) {
        String value = progress.get(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 全部进度（不可变视图）。 */
    public Map<Integer, String> progress() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(progress));
    }

    /** v83 进图任务段使用的拼接进度串，顺序按持久化进度行保持。 */
    public String progressData() {
        return String.join("", progress.values());
    }
}
