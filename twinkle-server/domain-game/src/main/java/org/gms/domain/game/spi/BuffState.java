package org.gms.domain.game.spi;

import org.gms.domain.game.skill.ActiveBuff;
import java.util.Map;

/** 技能系统临时状态端口，持久化角色基础属性与临时增益分开保存。 */
public interface BuffState extends ProgressionState {
    public Map<Long, ActiveBuff> buffs();
    public void putBuff(ActiveBuff buff);
    public void removeBuff(long mask);
    public long cooldown(int skillId);
    public void setCooldown(int skillId, long expiresAt);
}
