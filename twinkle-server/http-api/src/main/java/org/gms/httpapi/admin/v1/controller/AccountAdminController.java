package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;
import org.gms.httpapi.application.admin.AccountOperations;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;

import java.util.Map;

/** Web 控制台账号管理：分页检索、角色快照、封禁/禁言与强制下线。 */
@Controller(ApiRoutes.ADMIN_V1 + "/accounts")
@Produces(MediaType.APPLICATION_JSON)
public final class AccountAdminController {
    private final AccountOperations logic;
    public AccountAdminController(AccountOperations logic) { this.logic = logic; }
    @Get
    public HttpResponse<?> list(@QueryValue(defaultValue = "") String query,
                                @QueryValue(defaultValue = "all") String status,
                                @QueryValue(defaultValue = "0") int offset,
                                @QueryValue(defaultValue = "20") int limit){ return logic.list(query, status, offset, limit); }

    @Post
    public HttpResponse<?> create(HttpRequest<?> request, @Body Map<String, Object> body){ return logic.create(request, body); }

    @Put("/{accountId}")
    public HttpResponse<?> update(HttpRequest<?> request,
                                  @PathVariable long accountId,
                                  @Body Map<String, Object> body){ return logic.update(request, accountId, body); }

    @Delete("/{accountId}")
    public HttpResponse<?> delete(HttpRequest<?> request, @PathVariable long accountId){ return logic.delete(request, accountId); }

    @Get("/{accountId}")
    public HttpResponse<?> detail(@PathVariable long accountId){ return logic.detail(accountId); }

    @Put("/{accountId}/restrictions")
    public HttpResponse<?> updateRestrictions(HttpRequest<?> request,
                                              @PathVariable long accountId,
                                              @Body Map<String, Object> body){ return logic.updateRestrictions(request, accountId, body); }

    @Post("/{accountId}/force-offline")
    public HttpResponse<?> forceOffline(HttpRequest<?> request, @PathVariable long accountId){ return logic.forceOffline(request, accountId); }

    @Post("/{accountId}/temporary-password")
    public HttpResponse<?> generateTemporaryPassword(HttpRequest<?> request,
                                                     @PathVariable long accountId,
                                                     @Body Map<String, Object> body){ return logic.generateTemporaryPassword(request, accountId, body); }

}
