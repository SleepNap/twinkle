package org.gms.ai;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.ai.model.AiModelBundle;
import org.gms.ai.model.AiModelFactory;
import org.gms.ai.model.tool.AgentToolAudit;
import org.gms.ai.model.tool.GameStatTool;
import org.gms.ai.model.tool.ToolRouter;
import org.gms.ai.service.AiAssistant;
import org.gms.ai.service.AiDailySummaryScheduler;
import org.gms.ai.service.AiFacade;
import org.gms.ai.service.AiPlayerSupportAgent;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.observability.Metrics;
import org.gms.role.AiEnabledCondition;
import org.gms.service.admin.AdminService;

/**
 * ai 模块装配（AiServices 声明式 Agent + 真实/本地模型适配 + 只读工具）。
 *
 * <p>只依赖 core + data（红线 4.1 / ArchUnit 规则 1）：工具经 {@link AdminService}（core 公共
 * 契约）访问频道，不触碰游戏内存。模型由 {@link AiModelFactory} 按配置创建；本地规则模型只
 * 用于开发/测试，生产可使用 OpenAI-compatible 或 DeepSeek。
 *
 * <p><b>可选功能（2C2G 红线，同 WAL 按需启用）</b>：默认不装配，经
 * {@code twinkle.ai.enabled=true} 显式开启。管理进程专属（single 全内嵌；split 下仅
 * coordinator 角色装配，频道进程不启 AI）。
 */
@Factory
@Requires(condition = AiEnabledCondition.class)
public class AiConfig {

    @Bean
    @Singleton
    public ToolRouter toolRouter() {
        return new ToolRouter();
    }

    @Bean
    @Singleton
    public AiModelBundle aiModelBundle(
            ToolRouter toolRouter,
            @Property(name = "twinkle.ai.model.provider", defaultValue = "local-rule") String provider,
            @Property(name = "twinkle.ai.model.base-url", defaultValue = "") String baseUrl,
            @Property(name = "twinkle.ai.model.api-key", defaultValue = "") String apiKey,
            @Property(name = "twinkle.ai.model.name", defaultValue = "") String modelName,
            @Property(name = "twinkle.ai.model.temperature", defaultValue = "0.1") double temperature,
            @Property(name = "twinkle.ai.model.max-tokens", defaultValue = "1200") int maxTokens,
            @Property(name = "twinkle.ai.model.timeout-seconds", defaultValue = "45") int timeoutSeconds) {
        return AiModelFactory.create(provider, baseUrl, apiKey, modelName,
                temperature, maxTokens, timeoutSeconds, toolRouter);
    }

    @Bean
    @Singleton
    public AgentToolAudit agentToolAudit(ToolExecutionAuditRepository repository,
                                         @Property(name = "twinkle.server.id", defaultValue = "twinkle-local")
                                         String serverId) {
        return new AgentToolAudit(repository, serverId);
    }

    @Bean
    @Singleton
    public GameStatTool gameStatTool(AdminService adminService, AccountRepository accountRepository,
                                     CharacterRepository characterRepository,
                                     InventoryItemRepository inventoryRepository,
                                     Metrics metrics, AgentToolAudit audit) {
        return new GameStatTool(adminService, accountRepository, characterRepository,
                inventoryRepository, metrics, audit);
    }

    /**
     * AiServices 声明式 Agent：注入本地规则模型 + 工具，生成 {@link AiAssistant} 代理。
     * 流式：同一模型实现 StreamingChatModel，Agent 流式与工具调用原生合一。
     */
    @Bean
    @Singleton
    public AiAssistant aiAssistant(AiModelBundle model, GameStatTool tool,
                                   @Property(name = "twinkle.ai.memory.max-messages", defaultValue = "20")
                                   int maxMessages) {
        return dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model.chatModel())
                .streamingChatModel(model.streamingChatModel())
                .chatMemoryProvider(ignored -> dev.langchain4j.memory.chat.MessageWindowChatMemory
                        .withMaxMessages(Math.max(4, maxMessages)))
                .tools(tool)
                .build();
    }

    @Bean
    @Singleton
    public AiFacade aiFacade(AiAssistant assistant, AiUsageRepository usageRepository,
                             AiModelBundle model,
                             @Property(name = "twinkle.ai.memory.max-conversations", defaultValue = "100")
                             int maxConversations) {
        return new AiFacade(assistant, usageRepository, model, maxConversations);
    }

    /** 玩家聊天入口的异步适配器；独立小线程池隔离外部模型延迟。 */
    @Bean(preDestroy = "close")
    @Singleton
    public AiPlayerSupportAgent playerSupportAgent(
            AiFacade aiFacade,
            @Property(name = "twinkle.ai.player.worker-threads", defaultValue = "1") int workerThreads) {
        return new AiPlayerSupportAgent(aiFacade, workerThreads);
    }

    @Bean
    @Singleton
    public AiDailySummaryScheduler aiDailySummaryScheduler(AiFacade aiFacade, Metrics metrics) {
        return new AiDailySummaryScheduler(aiFacade, metrics);
    }
}
