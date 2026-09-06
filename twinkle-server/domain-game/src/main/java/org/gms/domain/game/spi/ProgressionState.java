package org.gms.domain.game.spi;

import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.quest.QuestChange;

/** 成长系统使用的稳定状态端口，避免可替换逻辑依赖玩家具体实现。 */
public interface ProgressionState extends CharacterState {
    public int getAp();
    public void setAp(int value);
    public String getSp();
    public void setSp(String value);
    public void setStrStat(short value);
    public void setDexStat(short value);
    public void setIntStat(short value);
    public void setLukStat(short value);
    public SkillEntry getSkill(int skillId);
    public void putSkill(SkillEntry skill);
    public boolean applyQuestChange(QuestChange change);
}
