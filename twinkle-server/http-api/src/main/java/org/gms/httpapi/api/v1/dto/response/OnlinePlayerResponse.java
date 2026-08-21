package org.gms.httpapi.api.v1.dto.response;

/** v1 在线角色摘要。 */
public record OnlinePlayerResponse(long characterId, String name, int mapId, int level, int job) {
}
