package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.gms.concurrent.ThreadManager;
import org.gms.httpapi.version.ApiRoutes;
import org.gms.service.admin.AdminService;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;
import org.gms.i18n.I18n;

/** 批量发奖逐人执行与返回结果；权限、原因和审计沿用统一管理过滤器。 */
@Controller(ApiRoutes.ADMIN_V1 + "/rewards")
public final class RewardController {
    public record Batch(String batchId, List<Long> characterIds, Map<Integer, Integer> items,
                        int meso, int experience) { }

    private final AdminService admin;
    private final ThreadManager background;

    public RewardController(AdminService admin, ThreadManager background) {
        this.admin = admin; this.background = background;
    }

    @Post
    public CompletableFuture<List<RewardResult>> grant(@Body Batch batch) {
        if (batch == null || batch.characterIds() == null || batch.characterIds().isEmpty()
                || batch.characterIds().size() > 100) throw invalid();
        try { new RewardGrant(batch.batchId(), 1, batch.items(), batch.meso(), batch.experience()); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        List<CompletableFuture<RewardResult>> results = batch.characterIds().stream().map(id -> {
            if (id == null || id <= 0) return CompletableFuture.completedFuture(
                    new RewardResult(batch.batchId(), id == null ? 0 : id, RewardResult.Status.INVALID));
            RewardGrant grant = new RewardGrant(batch.batchId(), id, batch.items(), batch.meso(), batch.experience());
            try {
                return background.supplyAsync(() -> admin.grantReward(grant))
                        .exceptionally(error -> RewardResult.of(grant, RewardResult.Status.FAILED));
            } catch (RuntimeException rejected) {
                return CompletableFuture.completedFuture(RewardResult.of(grant, RewardResult.Status.FAILED));
            }
        }).toList();
        return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> results.stream().map(CompletableFuture::join).toList());
    }

    private static HttpStatusException invalid() {
        return new HttpStatusException(HttpStatus.BAD_REQUEST, I18n.message("error.reward.invalid"));
    }
}
