package org.gms.httpapi.api.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.gms.httpapi.api.v1.dto.error.SimpleErrorResponse;
import org.gms.httpapi.api.v1.dto.response.AccountResponse;
import org.gms.httpapi.api.v1.dto.response.CharacterResponse;
import org.gms.httpapi.api.v1.dto.response.OnlineResponse;
import org.gms.httpapi.api.v1.mapper.PublicApiV1Mapper;
import org.gms.httpapi.application.admin.AdminApiService;

import java.util.List;
import java.util.Optional;

/**
 * 第三方 API（架构 M3-1：/api/v1/*，限流 + 版本化）。
 *
 * <p>路径前缀已含版本号（v1）；限流由 {@code ApiRateLimitFilter} 拦截。
 * 数据面：
 * <ul>
 *   <li>角色/账号查询 → ①查 DB（只读）</li>
 *   <li>在线概览 → ③只读镜像</li>
 *   <li>踢下线 → ②经 AdminService（事务性操作经 service 接口）</li>
 * </ul>
 */
@Controller(ApiRoutes.PUBLIC_V1)
@Produces(MediaType.APPLICATION_JSON)
public final class ApiV1Controller {

    private final AdminApiService adminApiService;

    public ApiV1Controller(AdminApiService adminApiService) {
        this.adminApiService = adminApiService;
    }

    /** 在线概览（只读镜像，③）。 */
    @Get("/online")
    public OnlineResponse online() {
        return PublicApiV1Mapper.online(adminApiService.onlinePlayers());
    }

    /** 查账号（① 查 DB）。不存在 404。 */
    @Get("/account/{name}")
    @Operation(summary = "按名称查询账号", responses = {
            @ApiResponse(responseCode = "200", content = @Content(
                    schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> account(@PathVariable String name) {
        Optional<AdminApiService.AccountView> account = adminApiService.findAccount(name);
        return account.<HttpResponse<?>>map(a -> HttpResponse.ok(PublicApiV1Mapper.account(a)))
                .orElseGet(() -> HttpResponse.notFound(
                        new SimpleErrorResponse("account_not_found")));
    }

    /** 某账号在世界下的角色列表（① 查 DB）。 */
    @Get("/account/{accountId}/characters")
    public List<CharacterResponse> characters(@PathVariable long accountId) {
        return adminApiService.charactersFor(accountId, 0).stream()
                .map(PublicApiV1Mapper::character)
                .toList();
    }

    /** 踢下线（② 经 service 接口到频道）。 */
    @Delete("/characters/{characterId}/session")
    @Operation(summary = "结束在线角色会话", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> kick(@PathVariable long characterId) {
        boolean kicked = adminApiService.kick(characterId);
        if (kicked) {
            return HttpResponse.noContent();
        }
        return HttpResponse.notFound(new SimpleErrorResponse("character_not_online"));
    }
}
