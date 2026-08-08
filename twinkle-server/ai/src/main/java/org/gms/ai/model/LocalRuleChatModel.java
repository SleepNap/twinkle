package org.gms.ai.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.ai.model.tool.ToolRouter;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本地规则聊天模型（架构 M3-2：AI 工具不直踩游戏内存，模型自研）。
 *
 * <p>实现 LangChain4j {@link ChatModel} + {@link StreamingChatModel}，用确定性规则代替
 * 外部 LLM API：经 {@link ToolRouter} 判断用户意图 → 若命中工具意图则返回
 * {@link AiMessage#AiMessage(List)}（工具调用请求），由 AiServices 驱动真实的多工具调用
 * 循环；工具结果回来后生成最终报告文本。
 *
 * <p><b>为何自研</b>：M3 单进程 2C2G 红线 + 无外部 LLM key。Agent/工具/流式/结构化输出
 * 全走真实 LangChain4j API；接入真实 LLM 时只需把本类替换为 OpenAI/Ollama 的 ChatModel
 * 实现（装配层换 bean，工具与编排零改动）。
 */
public final class LocalRuleChatModel implements ChatModel, StreamingChatModel {

    private static final Logger LOG = LogManager.getLogger(LocalRuleChatModel.class);

    private final ToolRouter toolRouter;

    public LocalRuleChatModel(ToolRouter toolRouter) {
        this.toolRouter = toolRouter;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        UserMessage user = lastUserMessage(messages);
        if (user == null) {
            return ChatResponse.builder().aiMessage(new AiMessage("（无用户输入）")).build();
        }

        String text = user.singleText();
        // 若是工具执行结果回传（ToolExecutionResultMessage），生成最终报告
        String toolResult = lastToolResult(messages);
        if (toolResult != null) {
            String report = buildReport(toolResult, text);
            return ChatResponse.builder().aiMessage(new AiMessage(jsonIfRequested(request, report))).build();
        }

        // 规则路由：命中工具意图 → 返回工具调用请求
        String toolName = toolRouter.route(text);
        if (toolName != null) {
            String arguments = toolRouter.argumentsFor(toolName, text);
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(arguments)
                    .build();
            LOG.info("本地模型路由到工具: {} 参数={}", toolName, arguments);
            return ChatResponse.builder().aiMessage(new AiMessage(List.of(req))).build();
        }

        // 未命中工具：直接回复
        return ChatResponse.builder().aiMessage(new AiMessage("（本地规则模型）收到：" + text)).build();
    }

    /** 结构化输出请求（AiServices 解析 POJO 需要 JSON）时包装为 JSON。 */
    private static String jsonIfRequested(ChatRequest request, String report) {
        dev.langchain4j.model.chat.request.ResponseFormat format =
                request.parameters() == null ? null : request.parameters().responseFormat();
        if (format != null
                && format.type() == dev.langchain4j.model.chat.request.ResponseFormatType.JSON) {
            // 解析报告中的在线人数，输出 { "onlineCount": N, "summary": "..." }（单行、转义 JSON）
            int onlineCount = extractCount(report);
            String flat = report.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            return "{\"onlineCount\":" + onlineCount + ",\"summary\":\"" + flat + "\"}";
        }
        return report;
    }

    private static int extractCount(String report) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("在线总人数：(\\d+)").matcher(report);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        // 流式：单帧完整响应 + 结束
        ChatResponse response = doChat(request);
        handler.onCompleteResponse(response);
    }

    private static UserMessage lastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u) {
                return u;
            }
        }
        return null;
    }

    private static String lastToolResult(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage r) {
                return r.text();
            }
        }
        return null;
    }

    /** 汇总工具执行结果为最终报告。 */
    private static String buildReport(String toolResult, String originalQuery) {
        return "【AI 报表】" + originalQuery + "\n" + toolResult;
    }

    @Override
    public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        // ChatModel 与 StreamingChatModel 的默认实现冲突，显式合并
        return Set.of(dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return dev.langchain4j.model.ModelProvider.OTHER;
    }

    @Override
    public java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
        return java.util.List.of();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return DefaultChatRequestParameters.builder().build();
    }
}
