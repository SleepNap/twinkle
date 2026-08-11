package org.gms.service.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 未启用 AI 或当前拓扑不承载 AI 时使用的空实现。 */
public final class UnavailablePlayerSupportAgent implements PlayerSupportAgent {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public CompletionStage<Reply> ask(PlayerQuestion question) {
        return CompletableFuture.completedFuture(new Reply("AI 值班 GM 当前未启用。", List.of()));
    }
}
