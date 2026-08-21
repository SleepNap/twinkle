package org.gms.httpapi.api.v1.dto.response;

/** v1 角色响应。 */
public record CharacterResponse(Long id, String name, Integer level, Integer job, Integer map,
                                Integer meso) {
}
