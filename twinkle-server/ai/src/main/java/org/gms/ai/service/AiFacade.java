package org.gms.ai.service;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.AiUsageEntity;
import org.gms.data.repo.AiUsageRepository;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 服务门面（架构 M3-2：AI 请求编排 + 计费/观测落 SQLite）。
 *
 * <p>包装 {@link AiAssistant}（AiServices 声明式 Agent），每次对话记录
 * 工具名/请求/响应长度/耗时到 {@code ai_usage} 表（计费复用 Dao 设计）。
 * 暴露调用次数计数（观测）。
 *
 * <p>本类不加 @Singleton——由 bootstrap 装配。
 */
@Log4j2
public final class AiFacade {



    private final AiAssistant assistant;
    private final AiUsageRepository usageRepository;
    private final AtomicLong callCount = new AtomicLong();

    public AiFacade(AiAssistant assistant, AiUsageRepository usageRepository) {
        this.assistant = assistant;
        this.usageRepository = usageRepository;
    }

    /** 阻塞对话（工具自动循环）。返回最终文本，记录计费。 */
    public String chat(String message) {
        long start = System.nanoTime();
        try {
            String reply = assistant.chat(message);
            record("chat", message, reply, start);
            return reply;
        } catch (RuntimeException e) {
            log.error("AI 对话异常", e);
            throw e;
        }
    }

    /** 流式对话：返回 TokenStream（调用方订阅增量）。 */
    public dev.langchain4j.service.TokenStream stream(String message) {
        return assistant.stream(message);
    }

    /** 结构化报表（POJO 自动解析）。 */
    public OnlineReport onlineReport() {
        long start = System.nanoTime();
        OnlineReport report = assistant.onlineReport("在线统计");
        record("online_report", "在线统计", String.valueOf(report.getOnlineCount()), start);
        return report;
    }

    /** 累计调用次数（观测）。 */
    public long callCount() {
        return callCount.get();
    }

    private void record(String toolName, String request, String reply, long startNanos) {
        callCount.incrementAndGet();
        AiUsageEntity usage = new AiUsageEntity();
        usage.setToolName(toolName);
        usage.setRequestText(request.length() > 500 ? request.substring(0, 500) : request);
        usage.setResponseLength(reply == null ? 0 : reply.length());
        usage.setElapsedMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        try {
            usageRepository.insert(usage);
        } catch (RuntimeException e) {
            log.warn("AI 使用记录落库失败（不影响对话）", e);
        }
    }
}
