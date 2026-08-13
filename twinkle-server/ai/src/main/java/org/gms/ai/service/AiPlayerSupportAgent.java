package org.gms.ai.service;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.service.agent.PlayerSupportAgent;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 将服务端 Agent 接入玩家聊天的异步适配器；模型调用绝不占用频道 IO 线程。 */
@Log4j2
public final class AiPlayerSupportAgent implements PlayerSupportAgent, AutoCloseable {

    private final AiFacade facade;
    private final ExecutorService executor;

    public AiPlayerSupportAgent(AiFacade facade, int workerThreads) {
        this.facade = Objects.requireNonNull(facade, "facade");
        AtomicInteger sequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerThreads), runnable -> {
            Thread thread = new Thread(runnable, "twinkle-player-agent-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public CompletionStage<Reply> ask(PlayerQuestion question) {
        Objects.requireNonNull(question, "question");
        return CompletableFuture.supplyAsync(() -> {
            String conversationId = "player:" + question.characterId();
            AiFacade.AgentReply result = facade.investigate(
                    conversationId,
                    "当前玩家角色名=" + question.characterName() + "，角色ID=" + question.characterId()
                            + "。玩家问题：" + question.message(),
                    UUID.randomUUID().toString(),
                    "player:" + question.characterId(),
                    "game-session:" + question.sessionId(),
                    "game-chat");
            return new Reply(result.reply(), result.auditRefs());
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        log.info(I18n.message("log.ai.player_executor_closed"));
    }
}
