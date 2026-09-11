package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.gms.concurrent.ThreadManager;
import org.gms.module.ModuleRegistry;
import org.gms.module.ModuleRuntime;
import org.gms.httpapi.version.ApiRoutes;
import org.gms.service.admin.AdminService;
import org.gms.service.admin.LogicReloadReport;

/** 实际制品加载入口；模块名固定映射到 incoming 目录，HTTP 不接受任意文件路径。 */
@Controller(ApiRoutes.ADMIN_V1 + "/reload/logic")
public final class LogicReloadController {
    private final ModuleRegistry modules;
    private final AdminService admin;
    private final ThreadManager background;
    public LogicReloadController(ModuleRegistry modules, AdminService admin, ThreadManager background) {
        this.modules = modules; this.admin = admin; this.background = background;
    }

    @Get public CompletableFuture<Map<String, ModuleRuntime.Status>> versions() {
        return background.supplyAsync(modules::status);
    }

    @Post public CompletableFuture<HttpResponse<?>> reload(@QueryValue(defaultValue = "game-logic") String module) {
        return background.supplyAsync(() -> {
            if (!List.of("game-logic", "login-logic", "admin-logic", "query-logic", "coordinator-logic").contains(module))
                return HttpResponse.badRequest(Map.of("error", "unknown_logic_module"));
            try {
                LogicReloadReport result = modules.contains(module)
                        ? new LogicReloadReport(List.of(modules.reload(module))) : admin.reloadLogic(module);
                return result.successful() ? HttpResponse.ok(result) : HttpResponse.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "logic_reload_partial", "message", result.updates().toString(),
                                "updates", result.updates()));
            } catch (Exception failure) {
                return HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("error", "logic_reload_rejected",
                        "message", String.valueOf(failure.getMessage())));
            }
        });
    }
}
