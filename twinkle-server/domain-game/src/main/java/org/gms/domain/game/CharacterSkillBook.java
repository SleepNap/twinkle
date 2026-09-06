package org.gms.domain.game;

import org.gms.domain.game.skill.SkillEntry;

import java.util.HashMap;
import java.util.Map;

/** 玩家技能子聚合。 */
public final class CharacterSkillBook {
    private final Map<Integer, SkillEntry> entries = new HashMap<>();

    public SkillEntry get(int skillId) { return entries.get(skillId); }
    public void put(SkillEntry entry) { entries.put(entry.skillId(), entry); }
    public void remove(int skillId) { entries.remove(skillId); }
    public Map<Integer, SkillEntry> snapshot() { return Map.copyOf(entries); }
}
