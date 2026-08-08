package org.gms.ai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.gms.ai.model.LocalRuleChatModel;
import org.gms.ai.model.tool.GameStatTool;
import org.gms.ai.model.tool.ToolRouter;
import org.gms.data.repo.AccountRepository;
import org.gms.observability.NoopMetrics;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-2 验收：AiServices Agent + @Tool 工具调用循环 + 流式（架构 M3-2 第 2 节）。
 *
 * <p>本地规则模型驱动真实 LangChain4j 工具调用循环：查询含"在线/统计"指令 → 模型触发
 * {@code GameStatTool.queryOnlineStats} → 工具经 {@link AdminService} 取数 → 生成报表。
 * 验证：阻塞 chat 触发工具并汇总；stream 流式收到完整响应；结构化输出。
 */
class AiAgentTest {

    /** 假 AdminService：返回 2 个在线玩家（不依赖真频道）。 */
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
    }

    private static final class FakeAccountRepo implements AccountRepository {
        @Override
        public java.util.Optional<org.gms.data.entity.Account> findByName(String name) {
            return java.util.Optional.empty();
        }
    }

    private LocalRuleChatModel model() {
        return new LocalRuleChatModel(new ToolRouter());
    }

    @Test
    void agentTriggersToolAndBuildsReport() {
        GameStatTool tool = new GameStatTool(new FakeAdmin(), new FakeAccountRepo(), new NoopMetrics());
        AiAssistant assistant = dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model())
                .streamingChatModel(model())
                .tools(tool)
                .build();

        String reply = assistant.chat("帮我看看当前在线统计情况");

        assertThat(reply).contains("在线总人数：2");
        assertThat(reply).contains("Hero");
        assertThat(reply).contains("Mage");
    }

    @Test
    void nonToolMessageReturnsDirectReply() {
        GameStatTool tool = new GameStatTool(new FakeAdmin(), new FakeAccountRepo(), new NoopMetrics());
        AiAssistant assistant = dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model())
                .streamingChatModel(model())
                .tools(tool)
                .build();

        String reply = assistant.chat("你好，介绍一下自己");

        assertThat(reply).contains("本地规则模型");
    }

    @Test
    void streamingDeliversCompleteResponse() throws Exception {
        GameStatTool tool = new GameStatTool(new FakeAdmin(), new FakeAccountRepo(), new NoopMetrics());
        AiAssistant assistant = dev.langchain4j.service.AiServices.builder(AiAssistant.class)
                .chatModel(model())
                .streamingChatModel(model())
                .tools(tool)
                .build();

        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        TokenStream stream = assistant.stream("在线统计");
        // TokenStream 是响应式链：onCompleteResponse/onError 回调后 start()
        AtomicReference<ChatResponse> resp = new AtomicReference<>();
        TokenStream chained = stream
                .onPartialResponse(finalText::set)
                .onCompleteResponse(resp::set)
                .onError(error::set);
        chained.start();
        Thread.sleep(500);  // 本地模型同步，start() 后已派发完成

        assertThat(error.get()).isNull();
        ChatResponse response = resp.get();
        assertThat(response).isNotNull();
        AiMessage aiMessage = response.aiMessage();
        assertThat(aiMessage.text()).contains("在线总人数：2");
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
}
