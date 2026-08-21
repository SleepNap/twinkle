package org.gms.httpapi.api.v1.dto.response;

/** v1 账号响应；字段名已冻结。 */
public record AccountResponse(Long id, String name, boolean banned, Integer gender,
                              Integer characterslots) {
}
