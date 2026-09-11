package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;
import org.gms.httpapi.application.query.CharacterQueries;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;

/** 管理控制台角色持久化详情聚合，只读且不触碰频道内存对象。 */
@Controller(ApiRoutes.ADMIN_V1 + "/accounts/{accountId}/characters")
@Produces(MediaType.APPLICATION_JSON)
public final class CharacterAdminController {
    private final CharacterQueries logic;
    public CharacterAdminController(CharacterQueries logic) { this.logic = logic; }
    @Get("/{characterId}")
    public HttpResponse<?> detail(@PathVariable long accountId, @PathVariable long characterId){ return logic.detail(accountId, characterId); }

}
