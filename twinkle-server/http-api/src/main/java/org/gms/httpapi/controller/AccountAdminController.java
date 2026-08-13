package org.gms.httpapi.controller;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.entity.Account;
import org.gms.data.repo.AccountRepository;

import java.util.List;
import java.util.Map;

/** Web 控制台账号搜索（供签发 API Key 时批量选择账号）。 */
@Controller("/admin/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
public final class AccountAdminController {

    private final AccountRepository accountRepository;

    public AccountAdminController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 按账号名模糊搜索，返回 id + name。 */
    @Get
    public Map<String, Object> list(@QueryValue(defaultValue = "") String query,
                                    @QueryValue(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<Account> accounts = accountRepository.findByNameLike(query, safeLimit);
        List<Map<String, Object>> result = accounts.stream()
                .map(account -> Map.<String, Object>of("id", account.getId(), "name", account.getName()))
                .toList();
        return Map.of("accounts", result);
    }
}
