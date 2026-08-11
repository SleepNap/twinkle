package org.gms.ai.service;

import org.gms.ai.model.AiModelBundle;
import org.gms.ai.model.LocalRuleChatModel;
import org.gms.ai.model.tool.AgentToolAudit;
import org.gms.ai.model.tool.GameStatTool;
import org.gms.ai.model.tool.ToolRouter;
import org.gms.data.entity.AiUsageEntity;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.observability.Metrics;
import org.gms.observability.NoopMetrics;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-2 计费/调度测试：AiFacade 每次对话记录 ai_usage，每日总结调度可观测（架构 M3-2）。
 *
 * <p>验证：
 * <ul>
 *   <li>AiFacade.chat 后 ai_usage 落库（工具名/请求/响应长度/耗时）。</li>
 *   <li>每日总结调度 runSummary 生成报表 + 埋点计数。</li>
 * </ul>
 */
class AiFacadeBillingTest {

    private static final class MemoryUsageRepo implements AiUsageRepository {
        final List<AiUsageEntity> records = new CopyOnWriteArrayList<>();
        final AtomicLong counter = new AtomicLong();

        @Override
        public void insert(AiUsageEntity usage) {
            records.add(usage);
        }

        @Override
        public long count() {
            return records.size();
        }
    }

    private static final class FakeAdmin implements AdminService {
        @Override
        public ChannelSummary onlineSummary() {
            return new ChannelSummary(1, 1, List.of(new OnlinePlayer(1L, "Hero", 1, 10, 0)));
        }

        @Override
        public boolean kick(long characterId) {
            return false;
        }

        @Override
        public int reloadScripts() {
            return 0;
        }

        @Override
        public void requestRestart() {
        }

        @Override
        public org.gms.hotreload.RestartCoordinator.Phase restartPhase() {
            return org.gms.hotreload.RestartCoordinator.Phase.RUNNING;
        }
    }

    private static final class EmptyAccounts implements AccountRepository {
        @Override
        public Optional<Account> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public void update(Account account) {
            // 测试桩：无需落库
        }
    }

    private static final class EmptyCharacters implements CharacterRepository {
        @Override
        public List<Character> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<Character> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(Character chr) {
        }

        @Override
        public void save(Character chr) {
        }
    }

    private static final class EmptyInventory implements InventoryItemRepository {
        @Override
        public List<InventoryItemEntity> findByCharacterId(long characterId) {
            return List.of();
        }

        @Override
        public void insert(InventoryItemEntity item) {
        }

        @Override
        public void replaceAll(long characterId, List<InventoryItemEntity> items) {
        }
    }

    private static final class MemoryAudit implements ToolExecutionAuditRepository {
        private final List<ToolExecutionAudit> records = new CopyOnWriteArrayList<>();

        @Override
        public void insert(ToolExecutionAudit audit) {
            records.add(audit);
        }

        @Override
        public Optional<ToolExecutionAudit> findByAuditRef(String auditRef) {
            return records.stream().filter(record -> auditRef.equals(record.getAuditRef())).findFirst();
        }

        @Override
        public long count() {
            return records.size();
        }
    }

    private AiFacade facade(MemoryUsageRepo usageRepo) {
        LocalRuleChatModel model = new LocalRuleChatModel(new ToolRouter());
        GameStatTool tool = new GameStatTool(new FakeAdmin(), new EmptyAccounts(), new EmptyCharacters(),
                new EmptyInventory(), new NoopMetrics(), new AgentToolAudit(new MemoryAudit(), "test-server"));
        AiAssistant assistant = dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(tool)
                .build();
        return new AiFacade(assistant, usageRepo,
                new AiModelBundle(model, model, "local-rule", "deterministic", false), 10);
    }

    @Test
    void chatRecordsUsage() {
        MemoryUsageRepo usageRepo = new MemoryUsageRepo();
        AiFacade facade = facade(usageRepo);

        String reply = facade.chat("在线统计");

        assertThat(reply).contains("在线总人数：1");
        assertThat(facade.callCount()).isEqualTo(1);
        assertThat(usageRepo.records).hasSize(1);
        AiUsageEntity record = usageRepo.records.get(0);
        assertThat(record.getToolName()).isEqualTo("agent:local-rule/deterministic");
        assertThat(record.getRequestText()).startsWith("sha256=").doesNotContain("在线统计");
        assertThat(record.getResponseLength()).isGreaterThan(0);
    }

    @Test
    void dailySummaryRunsAndReports() {
        MemoryUsageRepo usageRepo = new MemoryUsageRepo();
        AiFacade facade = facade(usageRepo);
        Metrics metrics = new NoopMetrics();
        AiDailySummaryScheduler scheduler = new AiDailySummaryScheduler(facade, metrics);

        scheduler.runSummary();

        assertThat(facade.callCount()).isEqualTo(1);
        assertThat(scheduler.lastRunEpoch()).isGreaterThan(0);
        assertThat(scheduler.errorCount()).isZero();
        scheduler.close();
    }

    @Test
    void sameConversationIdIsIsolatedBySubject() {
        AiFacade facade = facade(new MemoryUsageRepo());
        facade.investigate("shared-conversation", "你好", "request-a",
                "subject-a", "credential-a", "api");
        facade.investigate("shared-conversation", "你好", "request-b",
                "subject-b", "credential-b", "api");

        assertThat(facade.closeConversation("shared-conversation", "subject-a")).isTrue();
        assertThat(facade.closeConversation("shared-conversation", "subject-b")).isTrue();
    }
}
