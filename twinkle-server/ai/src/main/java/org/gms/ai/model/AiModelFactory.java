package org.gms.ai.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.gms.ai.model.tool.ToolRouter;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * 服务端 Agent 模型工厂。
 *
 * <p>{@code local-rule} 用于无外网测试；{@code openai-compatible}/{@code deepseek} 使用
 * LangChain4j 的 OpenAI-compatible 适配，可连接 DeepSeek、Qwen、Ollama、LM Studio 等。
 * 密钥只从启动配置注入，本类从不记录请求、响应、base URL 或密钥。
 */
public final class AiModelFactory {

    private AiModelFactory() {
    }

    /** 按显式配置创建模型；非法或缺失的外部模型配置在启动期失败。 */
    public static AiModelBundle create(String provider, String baseUrl, String apiKey, String modelName,
                                       double temperature, int maxTokens, int timeoutSeconds,
                                       ToolRouter toolRouter) {
        String normalizedProvider = normalized(provider, "local-rule").toLowerCase(Locale.ROOT);
        if ("local-rule".equals(normalizedProvider)) {
            LocalRuleChatModel model = new LocalRuleChatModel(toolRouter);
            return new AiModelBundle(model, model, "local-rule", "deterministic", false);
        }
        if (!"openai-compatible".equals(normalizedProvider) && !"deepseek".equals(normalizedProvider)) {
            throw new IllegalArgumentException("不支持的 AI 模型提供方: " + normalizedProvider);
        }

        String effectiveBaseUrl = normalized(baseUrl,
                "deepseek".equals(normalizedProvider) ? "https://api.deepseek.com/v1" : "");
        String effectiveModel = normalized(modelName,
                "deepseek".equals(normalizedProvider) ? "deepseek-chat" : "");
        validateExternalConfig(effectiveBaseUrl, apiKey, effectiveModel);
        String effectiveApiKey = apiKey == null || apiKey.isBlank() ? "none" : apiKey;
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        double safeTemperature = Math.max(0.0, Math.min(2.0, temperature));
        int safeMaxTokens = Math.max(64, maxTokens);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(effectiveBaseUrl)
                .apiKey(effectiveApiKey)
                .modelName(effectiveModel)
                .temperature(safeTemperature)
                .maxTokens(safeMaxTokens)
                .timeout(timeout)
                .maxRetries(2)
                .parallelToolCalls(false)
                .logRequests(false)
                .logResponses(false)
                .build();
        OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(effectiveBaseUrl)
                .apiKey(effectiveApiKey)
                .modelName(effectiveModel)
                .temperature(safeTemperature)
                .maxTokens(safeMaxTokens)
                .timeout(timeout)
                .parallelToolCalls(false)
                // DeepSeek/Qwen 的流式工具调用会在每个 chunk 重发完整 call id。
                .accumulateToolCallId(false)
                .logRequests(false)
                .logResponses(false)
                .build();
        return new AiModelBundle(chatModel, streamingModel, normalizedProvider, effectiveModel, true);
    }

    private static void validateExternalConfig(String baseUrl, String apiKey, String modelName) {
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("外部 AI 模型必须配置 twinkle.ai.model.base-url");
        }
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("外部 AI 模型必须配置 twinkle.ai.model.name");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI 模型 base URL 非法", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("AI 模型 base URL 只允许 http/https 完整地址");
        }
        if ((apiKey == null || apiKey.isBlank()) && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("远程 AI 模型必须通过 TWINKLE_LLM_API_KEY 注入密钥");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
