package org.gms.domain.game.quest;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.concurrent.GameExecution;
import org.gms.i18n.I18n;

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
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private GameExecution execution;

    public void bindExecution(GameExecution owner) {
        owner.requireOwner();
        if (execution != null && execution != owner)
            throw new IllegalStateException(I18n.message("error.execution.quest_owner"));
        execution = owner;
    }

    private void requireStateAccess() { if (execution != null) execution.requireOwner(); }


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
        requireStateAccess();
        progress.put(key, Integer.toString(value));
    }

    public void setProgressText(int key, String value) {
        requireStateAccess();
        progress.put(key, value == null ? "" : value);
    }

    public int getProgress(int key) {
        requireStateAccess();
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
        requireStateAccess();
        return Collections.unmodifiableMap(new LinkedHashMap<>(progress));
    }

    /** v83 进图任务段使用的拼接进度串，顺序按持久化进度行保持。 */
    public String progressData() {
        requireStateAccess();
        return String.join("", progress.values());
    }

    // 写入口校验归属，防止调用方保留任务引用后跨线程修改。
    public void setState(State value) { requireStateAccess(); this.state = value; }
    public void setCompletionTime(long value) { requireStateAccess(); this.completionTime = value; }
    public void setExpirationTime(long value) { requireStateAccess(); this.expirationTime = value; }
    public void setForfeited(int value) { requireStateAccess(); this.forfeited = value; }
    public void setCompleted(int value) { requireStateAccess(); this.completed = value; }
    public void setInfoNumber(int value) { requireStateAccess(); this.infoNumber = value; }
}
