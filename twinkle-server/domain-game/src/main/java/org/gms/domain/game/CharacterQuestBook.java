package org.gms.domain.game;

import org.gms.domain.game.quest.QuestStatus;

import java.util.HashMap;
import java.util.Map;

/** 玩家任务子聚合。 */
public final class CharacterQuestBook {
    private final Map<Integer, QuestStatus> entries = new HashMap<>();

    public QuestStatus get(int questId) { return entries.get(questId); }
    public void put(QuestStatus status) { entries.put(status.getQuestId(), status); }
    public Map<Integer, QuestStatus> snapshot() { return Map.copyOf(entries); }
}
