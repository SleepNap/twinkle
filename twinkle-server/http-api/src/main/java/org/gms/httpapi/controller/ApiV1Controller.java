package org.gms.httpapi.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.httpapi.service.AdminApiService;
import org.gms.service.admin.AdminService;

import java.util.List;
import java.util.Map;
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
@Controller("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public final class ApiV1Controller {

    private final AdminApiService adminApiService;

    public ApiV1Controller(AdminApiService adminApiService) {
        this.adminApiService = adminApiService;
    }

    /** 在线概览（只读镜像，③）。 */
    @Get("/online")
    public Map<String, Object> online() {
        List<AdminService.OnlinePlayer> players = adminApiService.onlinePlayers();
        return Map.of(
                "onlineCount", players.size(),
                "players", players);
    }

    /** 查账号（① 查 DB）。不存在 404。 */
    @Get("/account/{name}")
    public HttpResponse<?> account(@PathVariable String name) {
        Optional<Account> account = adminApiService.findAccount(name);
        return account.<HttpResponse<?>>map(a -> HttpResponse.ok(accountDto(a)))
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "account_not_found")));
    }

    /** 某账号在世界下的角色列表（① 查 DB）。 */
    @Get("/account/{accountId}/characters")
    public List<Map<String, Object>> characters(@PathVariable long accountId) {
        return adminApiService.charactersFor(accountId, 0).stream()
                .map(ApiV1Controller::characterDto)
                .toList();
    }

    /** 踢下线（② 经 service 接口到频道）。 */
    @Delete("/characters/{characterId}/session")
    public HttpResponse<?> kick(@PathVariable long characterId) {
        boolean kicked = adminApiService.kick(characterId);
        if (kicked) {
            return HttpResponse.noContent();
        }
        return HttpResponse.notFound(Map.of("error", "character_not_online"));
    }

    private static Map<String, Object> accountDto(Account a) {
        return Map.of(
                "id", a.getId(),
                "name", a.getName(),
                "banned", a.getBanned() == 1,
                "gender", a.getGender(),
                "characterslots", a.getCharacterSlots());
    }

    private static Map<String, Object> characterDto(Character c) {
        return Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "level", c.getLevel(),
                "job", c.getJob(),
                "map", c.getMap(),
                "meso", c.getMeso());
    }
}
