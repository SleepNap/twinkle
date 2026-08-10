package org.gms.ai;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.ai.model.LocalRuleChatModel;
import org.gms.ai.model.tool.GameStatTool;
import org.gms.ai.model.tool.ToolRouter;
import org.gms.ai.service.AiAssistant;
import org.gms.ai.service.AiDailySummaryScheduler;
import org.gms.ai.service.AiFacade;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.observability.Metrics;
import org.gms.role.AiEnabledCondition;
import org.gms.service.admin.AdminService;

/**
 * ai 模块装配（架构 M3-2：AiServices 声明式 Agent + @Tool + 本地规则模型）。
 *
 * <p>只依赖 core + data（红线 4.1 / ArchUnit 规则 1）：工具经 {@link AdminService}（core 公共
 * 契约）访问频道，不触碰游戏内存。模型为自研 {@link LocalRuleChatModel}；接入真实 LLM 时
 * 换 {@code ChatModel}/{@code StreamingChatModel} bean 即可（装配层替换，工具/编排零改动）。
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
    public LocalRuleChatModel localRuleChatModel(ToolRouter toolRouter) {
        return new LocalRuleChatModel(toolRouter);
    }

    @Bean
    @Singleton
    public GameStatTool gameStatTool(AdminService adminService, AccountRepository accountRepository, Metrics metrics) {
        return new GameStatTool(adminService, accountRepository, metrics);
    }

    /**
     * AiServices 声明式 Agent：注入本地规则模型 + 工具，生成 {@link AiAssistant} 代理。
     * 流式：同一模型实现 StreamingChatModel，Agent 流式与工具调用原生合一。
     */
    @Bean
    @Singleton
    public AiAssistant aiAssistant(LocalRuleChatModel model, GameStatTool tool) {
        return dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model)
                .streamingChatModel(model)
                .tools(tool)
                .build();
    }

    @Bean
    @Singleton
    public AiFacade aiFacade(AiAssistant assistant, AiUsageRepository usageRepository) {
        return new AiFacade(assistant, usageRepository);
    }

    @Bean
    @Singleton
    public AiDailySummaryScheduler aiDailySummaryScheduler(AiFacade aiFacade, Metrics metrics) {
        return new AiDailySummaryScheduler(aiFacade, metrics);
    }
}
