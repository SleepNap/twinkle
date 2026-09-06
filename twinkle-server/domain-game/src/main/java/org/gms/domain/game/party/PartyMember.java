package org.gms.domain.game.party;

/** 同频道队伍的不可变成员投影，不携带玩家对象或网络连接。 */
public record PartyMember(long id, long sessionId, String name, int job, int level, int mapId) { }
