package org.gms.domain.game.quest;

import java.util.Map;

/** 经过条件校验的任务状态与奖励事务，预期状态用于拒绝重放。 */
public record QuestChange(int questId, QuestStatus.State expected, QuestStatus.State target,
                          Map<Integer, Integer> items, Map<Integer, Integer> slotLimits,
                          int meso, int experience, long completedAt) {
    public QuestChange { items = Map.copyOf(items); slotLimits = Map.copyOf(slotLimits); }
}
