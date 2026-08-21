package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.httpapi.admin.AdminAuthFilter;
import org.gms.service.admin.AdminService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Web 控制台账号管理：分页检索、角色快照、封禁/禁言与强制下线。 */
@Controller(ApiRoutes.ADMIN_V1 + "/accounts")
@Produces(MediaType.APPLICATION_JSON)
public final class AccountAdminController {

    private final AccountRepository accountRepository;
    private final CharacterRepository characterRepository;
    private final AdminService adminService;

    public AccountAdminController(AccountRepository accountRepository,
                                  CharacterRepository characterRepository,
                                  AdminService adminService) {
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
        this.adminService = adminService;
    }

    /** 按账号名分页搜索；status=all|active|banned。 */
    @Get
    public HttpResponse<?> list(@QueryValue(defaultValue = "") String query,
                                @QueryValue(defaultValue = "all") String status,
                                @QueryValue(defaultValue = "0") int offset,
                                @QueryValue(defaultValue = "20") int limit) {
        String normalizedStatus = status == null ? "all" : status.trim().toLowerCase();
        if (!Set.of("all", "active", "banned").contains(normalizedStatus)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_status"));
        }
        Boolean banned = switch (normalizedStatus) {
            case "active" -> false;
            case "banned" -> true;
            default -> null;
        };
        AccountRepository.AccountPage page = accountRepository.findPage(query, banned,
                Math.max(0, offset), Math.max(1, Math.min(100, limit)));
        return HttpResponse.ok(Map.of(
                "total", page.total(),
                "offset", page.offset(),
                "limit", page.limit(),
                "accounts", page.records().stream().map(this::accountMap).toList()));
    }

    /** 账号详情与所有世界的角色快照。 */
    @Get("/{accountId}")
    public HttpResponse<?> detail(@PathVariable long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Set<Long> online = onlineCharacterIds();
        List<Map<String, Object>> characters = characterRepository.findByAccount(accountId).stream()
                .map(character -> characterMap(character, online.contains(character.getId())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", accountMap(account));
        result.put("characters", characters);
        return HttpResponse.ok(result);
    }

    /** 更新封禁与禁言状态；封禁账号时同步踢下该账号所有在线角色。 */
    @Put("/{accountId}/restrictions")
    public HttpResponse<?> updateRestrictions(HttpRequest<?> request,
                                              @PathVariable long accountId,
                                              @Body Map<String, Object> body) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Boolean banned = booleanField(body, "banned");
        Boolean muted = booleanField(body, "muted");
        if (banned == null && muted == null) {
            return HttpResponse.badRequest(Map.of("error", "restriction_fields_required"));
        }

        String before = restrictionSummary(account);
        if (banned != null) {
            account.setBanned(banned ? 1 : 0);
            account.setBanReason(banned ? safeString(body.get("banReason"), 256) : "");
        }
        if (muted != null) {
            account.setMute(muted ? 1 : 0);
        }

        int disconnected = 0;
        if (account.getBanned() == 1) {
            disconnected = disconnectAccount(accountId);
            account.setLoggedIn(0);
        }
        accountRepository.update(account);
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE, before);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, restrictionSummary(account));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.put("disconnected", disconnected);
        result.put("account", accountMap(account));
        return HttpResponse.ok(result);
    }

    /** 踢下账号全部在线角色，并修复可能残留的 logged_in 状态。 */
    @Post("/{accountId}/force-offline")
    public HttpResponse<?> forceOffline(HttpRequest<?> request, @PathVariable long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        int beforeLoggedIn = account.getLoggedIn();
        int disconnected = disconnectAccount(accountId);
        account.setLoggedIn(0);
        accountRepository.update(account);
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",loggedIn=" + beforeLoggedIn);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",loggedIn=0,disconnected=" + disconnected);
        return HttpResponse.ok(Map.of(
                "forcedOffline", true,
                "accountId", accountId,
                "disconnected", disconnected));
    }

    private int disconnectAccount(long accountId) {
        int disconnected = 0;
        for (Character character : characterRepository.findByAccount(accountId)) {
            if (adminService.kick(character.getId())) {
                disconnected++;
            }
        }
        return disconnected;
    }

    private Set<Long> onlineCharacterIds() {
        AdminService.ChannelSummary summary = adminService.onlineSummary();
        if (summary == null || summary.players() == null) {
            return Set.of();
        }
        return summary.players().stream().map(AdminService.OnlinePlayer::characterId).collect(Collectors.toSet());
    }

    private Map<String, Object> accountMap(Account account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", account.getId());
        result.put("name", account.getName());
        result.put("banned", account.getBanned() == 1);
        result.put("banReason", safeString(account.getBanReason(), 256));
        result.put("muted", account.getMute() != null && account.getMute() == 1);
        result.put("loggedIn", account.getLoggedIn() == 1);
        result.put("lastLogin", safeString(account.getLastLogin(), 64));
        result.put("createdAt", safeString(account.getCreatedAt(), 64));
        result.put("tempBan", safeString(account.getTempBan(), 64));
        result.put("characterSlots", account.getCharacterSlots());
        result.put("gender", account.getGender());
        return result;
    }

    private static Map<String, Object> characterMap(Character character, boolean online) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", character.getId());
        result.put("name", character.getName());
        result.put("world", character.getWorld());
        result.put("level", character.getLevel());
        result.put("job", character.getJob());
        result.put("map", character.getMap());
        result.put("meso", character.getMeso());
        result.put("fame", character.getFame());
        result.put("guildId", character.getGuildId());
        result.put("lastLogoutTime", safeString(character.getLastLogoutTime(), 64));
        result.put("online", online);
        return result;
    }

    private static Boolean booleanField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        return null;
    }

    private static String restrictionSummary(Account account) {
        return "accountId=" + account.getId()
                + ",banned=" + (account.getBanned() == 1)
                + ",muted=" + (account.getMute() != null && account.getMute() == 1);
    }

    private static String safeString(Object value, int max) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
