package org.gms.ai.model.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import org.gms.observability.Metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具（Tavily REST）。provider 非 tavily（默认 off）时 {@link #enabled()} 为 false，
 * 不注册进 Agent 工具列表；已启用时按查询词返回搜索结果摘要（标题 + URL）。
 *
 * <p>计费：本工具不感知积分——http-api 的 BillingService 依据 executedTools 里 {@code web_search}
 * 次数按固定积分扣减（配置 {@code twinkle.billing.websearch.cost}）。
 */
public final class WebSearchTool {

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]*)\"");

    private final String provider;
    private final String apiKey;
    private final AgentToolAudit audit;
    private final Metrics metrics;
    private final HttpClient client;

    public WebSearchTool(String provider, String apiKey, AgentToolAudit audit, Metrics metrics) {
        this.provider = provider == null ? "off" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.audit = audit;
        this.metrics = metrics;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 是否启用联网搜索（provider=tavily）。 */
    public boolean enabled() {
        return "tavily".equals(provider);
    }

    /** 联网搜索：返回搜索结果摘要（标题 + URL）。 */
    @Tool(name = "web_search", value = "联网搜索：按查询词返回搜索结果摘要（标题、URL），用于需要外部实时信息的调查")
    public String webSearch(@P("搜索查询词") String query, InvocationParameters parameters) {
        String safeQuery = validatedQuery(query);
        return audit.execute("gm.websearch.read", "query=" + safeQuery, "联网搜索", parameters, () -> {
            metrics.increment("ai.tool.web_search");
            return callTavily(safeQuery);
        });
    }

    private String callTavily(String query) {
        if (apiKey.isBlank()) {
            return "联网搜索未配置 API Key（twinkle.ai.websearch.api-key）。";
        }
        String body = "{\"api_key\":\"" + jsonEscape(apiKey) + "\",\"query\":\"" + jsonEscape(query)
                + "\",\"search_depth\":\"basic\",\"max_results\":5}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "联网搜索失败（HTTP " + response.statusCode() + "）。";
            }
            return summarize(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "联网搜索被中断。";
        } catch (Exception e) {
            return "联网搜索异常：" + e.getClass().getSimpleName() + "。";
        }
    }

    private static String summarize(String json) {
        List<String> titles = extractAll(json, TITLE_PATTERN);
        List<String> urls = extractAll(json, URL_PATTERN);
        if (titles.isEmpty()) {
            return "联网搜索无结果。";
        }
        StringBuilder sb = new StringBuilder("搜索结果（").append(titles.size()).append(" 条）：\n");
        for (int i = 0; i < titles.size(); i++) {
            sb.append(i + 1).append(". ").append(unescape(titles.get(i)));
            if (i < urls.size()) {
                sb.append(" — ").append(urls.get(i));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static List<String> extractAll(String json, Pattern pattern) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\n", " ").replace("\\/", "/");
    }

    private static String validatedQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 500) {
            throw new IllegalArgumentException("搜索查询词必须为 1-500 个字符");
        }
        return query.trim();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
