package org.gms.domain.script.host;

/**
 * 宿主对象契约 qm（quest manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code qm} 操作任务状态（开始/推进/完成/获取任务数据）。
 * v83 脚本兼容约定。
 */
public interface Qm {

    /** 角色当前是否在做该任务。 */
    boolean isStarted(int questId);

    /** 角色是否已完成该任务。 */
    boolean isCompleted(int questId);

    /** 让角色开始任务（v83 语义：forceStart 可能跨前置）。 */
    void startQuest(int questId);

    /** 让角色完成任务。 */
    void completeQuest(int questId);
}
