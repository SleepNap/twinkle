package org.gms.data.repo;

/** 落库前的任务进度投影；用任务 ID 关联尚未插入、还没有主键的任务状态。 */
public record QuestProgressSnapshot(int questId, int progressId, String progress) {
}
