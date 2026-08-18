package org.gms.ai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import org.gms.ai.model.LocalRuleChatModel;
import org.gms.ai.model.tool.AgentToolAudit;
import org.gms.ai.model.tool.GameStatTool;
import org.gms.ai.model.tool.ToolRouter;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.observability.NoopMetrics;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 服务端值班 GM：工具循环、会话、只读取证与审计验收。 */
class AiAgentTest {

    private static final class FakeAdmin implements AdminService {
        @Override
        public ChannelSummary onlineSummary() {
            return new ChannelSummary(2, 1, List.of(
                    new OnlinePlayer(1L, "Hero", 100000000, 10, 0),
                    new OnlinePlayer(2L, "Mage", 100000000, 12, 200)));
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

    private static final class FakeAccountRepo implements AccountRepository {
        @Override
        public Optional<Account> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public void update(Account account) {
        }

        @Override
        public void insert(Account account) {
        }

        @Override
        public Optional<Account> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<Account> findByNameLike(String query, int limit) {
            return List.of();
        }
    }

    private static final class FakeCharacterRepo implements CharacterRepository {
        private final Character hero;

        private FakeCharacterRepo() {
            hero = new Character();
            hero.setId(1L);
            hero.setAccountId(7L);
            hero.setName("Hero");
            hero.setLevel(10);
            hero.setJob(0);
            hero.setMap(100000000);
            hero.setMeso(12345);
            hero.setLastLogoutTime("2026-08-11T00:00:00Z");
        }

        @Override
        public List<Character> findByAccount(int accountId, int world) {
            return List.of(hero);
        }

        @Override
        public Optional<Character> findById(long id) {
            return id == 1L ? Optional.of(hero) : Optional.empty();
        }

        @Override
        public Optional<Character> findByName(String name) {
            return "Hero".equals(name) ? Optional.of(hero) : Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return "Hero".equals(name);
        }

        @Override
        public void insert(Character chr) {
        }

        @Override
        public void save(Character chr) {
        }
    }

    private static final class FakeInventoryRepo implements InventoryItemRepository {
        @Override
        public List<InventoryItemEntity> findByCharacterId(long characterId) {
            InventoryItemEntity item = new InventoryItemEntity();
            item.setCharacterId((int) characterId);
            item.setItemId(2000000);
            item.setInventoryType(2);
            item.setPosition(1);
            item.setQuantity(5);
            return List.of(item);
        }

        @Override
        public void insert(InventoryItemEntity item) {
        }

        @Override
        public void replaceAll(long characterId, List<InventoryItemEntity> items) {
        }
    }

    private static final class MemoryAuditRepo implements ToolExecutionAuditRepository {
        private final List<ToolExecutionAudit> records = new ArrayList<>();

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

    private static LocalRuleChatModel model() {
        return new LocalRuleChatModel(new ToolRouter());
    }

    private static GameStatTool tool(MemoryAuditRepo auditRepo) {
        return new GameStatTool(new FakeAdmin(), new FakeAccountRepo(), new FakeCharacterRepo(),
                new FakeInventoryRepo(), new NoopMetrics(), new AgentToolAudit(auditRepo, "test-server"));
    }

    private static AiAssistant assistant(GameStatTool tool) {
        LocalRuleChatModel model = model();
        return dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model)
                .streamingChatModel(model)
                .chatMemoryProvider(ignored -> dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(tool)
                .build();
    }

    private static InvocationParameters parameters() {
        return InvocationParameters.from(java.util.Map.of(
                "conversationId", "test-conversation",
                "requestId", "test-request",
                "subjectId", "tester",
                "credentialId", "test-key",
                "source", "test"));
    }

    private static InvocationParameters playerParameters(long characterId) {
        return InvocationParameters.from(java.util.Map.of(
                "conversationId", "player:" + characterId,
                "requestId", "player-request",
                "subjectId", "player:" + characterId,
                "credentialId", "game-session:1",
                "source", "game-chat"));
    }

    @Test
    void agentTriggersToolAndReturnsAuditRef() {
        MemoryAuditRepo auditRepo = new MemoryAuditRepo();
        Result<String> result = assistant(tool(auditRepo))
                .investigate("test-conversation", "帮我看看当前在线统计情况", parameters());

        assertThat(result.content()).contains("在线总人数：2", "Hero", "Mage", "auditRef=");
        assertThat(result.toolExecutions()).extracting(execution -> execution.request().name())
                .containsExactly("queryOnlineStats");
        assertThat(auditRepo.records).hasSize(1);
        assertThat(auditRepo.records.getFirst().getToolId()).isEqualTo("gm.online.stats.read");
        assertThat(auditRepo.records.getFirst().getSource()).isEqualTo("test");
    }

    @Test
    void inventoryInvestigationUsesPersistedSnapshotAndAuditsSensitiveKeyAsHash() {
        MemoryAuditRepo auditRepo = new MemoryAuditRepo();
        Result<String> result = assistant(tool(auditRepo))
                .investigate("test-conversation", "查询背包 Hero", parameters());

        assertThat(result.content()).contains("itemId=2000000", "在线未落盘变更不在内", "auditRef=");
        assertThat(auditRepo.records).hasSize(1);
        assertThat(auditRepo.records.getFirst().getParameterSummary())
                .startsWith("characterNameHash=")
                .doesNotContain("Hero");
    }

    @Test
    void playerChatCanReadOnlyItsOwnCharacterAndHidesOnlineNames() {
        MemoryAuditRepo auditRepo = new MemoryAuditRepo();
        GameStatTool tool = tool(auditRepo);

        assertThat(tool.queryPlayerInventory("Hero", playerParameters(1L)))
                .contains("itemId=2000000");
        assertThat(tool.queryOnlineStats(playerParameters(1L)))
                .contains("当前在线总人数：2", "地图 100000000：2 人")
                .doesNotContain("Hero", "Mage");
        assertThat(auditRepo.records).allSatisfy(record -> {
            assertThat(record.getRequiredScopes()).isEqualTo("player:self:read");
            assertThat(record.getAuthorizationResult()).isEqualTo("allowed");
        });
    }

    @Test
    void playerChatCannotReadAnotherCharacterOrAccount() {
        MemoryAuditRepo auditRepo = new MemoryAuditRepo();
        GameStatTool tool = tool(auditRepo);

        assertThatThrownBy(() -> tool.queryPlayerProfile("Hero", playerParameters(2L)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> tool.queryAccountStatus("admin", playerParameters(1L)))
                .isInstanceOf(SecurityException.class);
        assertThat(auditRepo.records).hasSize(2).allSatisfy(record ->
                assertThat(record.getAuthorizationResult()).isEqualTo("denied"));
    }

    @Test
    void rememberedConversationDoesNotReuseAStaleToolResult() {
        MemoryAuditRepo auditRepo = new MemoryAuditRepo();
        AiAssistant assistant = assistant(tool(auditRepo));

        String first = assistant.investigate("same-conversation", "在线统计", parameters()).content();
        String second = assistant.investigate("same-conversation", "查询背包 Hero", parameters()).content();

        assertThat(first).contains("在线总人数：2");
        assertThat(second).contains("itemId=2000000").doesNotContain("在线总人数：2");
        assertThat(auditRepo.records).hasSize(2);
    }

    @Test
    void nonToolMessageReturnsDirectReply() {
        String reply = assistant(tool(new MemoryAuditRepo()))
                .investigate("test-conversation", "你好，介绍一下自己", parameters()).content();

        assertThat(reply).contains("本地规则模型");
    }

    @Test
    void streamingDeliversCompleteResponse() throws Exception {
        AiAssistant assistant = assistant(tool(new MemoryAuditRepo()));
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        TokenStream stream = assistant.stream("test-conversation", "在线统计", parameters());
        stream.onCompleteResponse(response::set).onError(error::set).start();
        Thread.sleep(200);

        assertThat(error.get()).isNull();
        assertThat(response.get()).isNotNull();
        assertThat(response.get().aiMessage().text()).contains("在线总人数：2");
    }

    @Test
    void modelReturnsToolExecutionRequestForOnlineQuery() {
        ChatResponse response = model().doChat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("在线统计")))
                .build());

        AiMessage aiMessage = response.aiMessage();
        assertThat(aiMessage.hasToolExecutionRequests()).isTrue();
        assertThat(aiMessage.toolExecutionRequests())
                .extracting(dev.langchain4j.agent.tool.ToolExecutionRequest::name)
                .containsExactly("queryOnlineStats");
    }

    @Test
    void localFallbackUsesTrustedPlayerContextForToolArgument() {
        ToolRouter router = new ToolRouter();
        String prompt = "当前玩家角色名=Hero，角色ID=1。玩家问题：我的背包里还有药水吗";

        assertThat(router.route(prompt)).isEqualTo(ToolRouter.PLAYER_INVENTORY);
        assertThat(router.argumentsFor(ToolRouter.PLAYER_INVENTORY, prompt))
                .isEqualTo("{\"characterName\":\"Hero\"}");
    }
}
