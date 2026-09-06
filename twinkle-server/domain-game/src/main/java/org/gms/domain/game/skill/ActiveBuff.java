package org.gms.domain.game.skill;

/** 单项临时属性，绝对到期时间由服务端维护；重施只替换同一属性位。 */
public record ActiveBuff(long mask, int value, int skillId, long expiresAt) { }
