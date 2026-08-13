package org.gms.httpapi.capability;

import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.auth.ApiScopes;
import org.gms.httpapi.contract.ApiContract;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.i18n.I18n;
import org.gms.service.agent.ServerAgentService;
import org.gms.service.agent.UnavailableServerAgentService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 明确注册、按 Credential 可见性裁剪的首批 Tool 目录。 */
public final class ToolCatalogService {

    public static final String HEALTH_TOOL = "server.health.read";
    public static final String ONLINE_TOOL = "player.online.list";
    public static final String INVENTORY_TOOL = "player.inventory.read";
    public static final String AGENT_INVESTIGATE_TOOL = "server.agent.investigate";
    public static final String AGENT_CLOSE_TOOL = "server.agent.conversation.close";
    public static final String TOOL_VERSION = "1.0.0";
    public static final String CATALOG_VERSION = "catalog_0.3.0";

    private final ServerIdentity serverIdentity;
    private final Map<String, ToolSpec> specs;

    public ToolCatalogService(ServerIdentity serverIdentity, ServerAgentService serverAgent) {
        this.serverIdentity = serverIdentity;
        this.specs = Map.of(
                HEALTH_TOOL, healthSpec(),
                ONLINE_TOOL, onlineSpec(),
                INVENTORY_TOOL, inventorySpec(),
                AGENT_INVESTIGATE_TOOL, agentInvestigateSpec(serverAgent.available()),
                AGENT_CLOSE_TOOL, agentCloseSpec(serverAgent.available()));
    }

    /** 兼容独立目录单测：未传 Agent 时目录仍展示为不可用。 */
    public ToolCatalogService(ServerIdentity serverIdentity) {
        this(serverIdentity, new UnavailableServerAgentService());
    }

    public Map<String, Object> catalog(ApiPrincipal principal, String profile, String query) {
        String normalizedQuery = normalizeQuery(query);
        List<Map<String, Object>> tools = new ArrayList<>();
        specs.values().stream()
                .sorted(java.util.Comparator.comparing(ToolSpec::toolId))
                .filter(spec -> visible(principal, spec))
                .filter(spec -> matches(spec, profile, normalizedQuery))
                .map(ToolSpec::summary)
                .forEach(tools::add);

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", ApiContract.VERSION);
        result.put("catalogVersion", CATALOG_VERSION);
        result.put("permissionVersion", principal.permissionVersion());
        result.put("tools", tools);
        result.put("generatedAt", Instant.now().toString());
        return result;
    }

    public Optional<Map<String, Object>> detail(ApiPrincipal principal, String toolId) {
        ToolSpec spec = specs.get(toolId);
        if (spec == null || !visible(principal, spec)) {
            return Optional.empty();
        }
        return Optional.of(spec.detail());
    }

    public Optional<ToolSpec> executable(ApiPrincipal principal, String toolId, String toolVersion) {
        ToolSpec spec = specs.get(toolId);
        if (spec == null || !visible(principal, spec) || !spec.toolVersion().equals(toolVersion)) {
            return Optional.empty();
        }
        return Optional.of(spec);
    }

    public String etag(ApiPrincipal principal) {
        return "\"" + CATALOG_VERSION + ":" + principal.permissionVersion() + "\"";
    }

    private boolean visible(ApiPrincipal principal, ToolSpec spec) {
        return serverIdentity.serverId().equals(principal.serverId())
                && principal.permits(spec.requiredScope());
    }

    private static boolean matches(ToolSpec spec, String profile, String query) {
        if (profile != null && !profile.isBlank() && !"read-only".equals(profile)) {
            return false;
        }
        if (query.isBlank()) {
            return true;
        }
        String haystack = (spec.toolId() + " " + spec.title() + " " + spec.summaryText()
                + " " + String.join(" ", spec.tags())).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(I18n.message("error.catalog.query_too_long"));
        }
        return normalized;
    }

    private static ToolSpec healthSpec() {
        Map<String, Object> input = objectSchema(Map.of(), List.of());
        Map<String, Object> check = objectSchema(linkedMap(
                "id", Map.of("type", "string"),
                "status", Map.of("enum", List.of("up", "degraded", "down", "unknown")),
                "message", Map.of("type", List.of("string", "null"))), List.of("id", "status"));
        Map<String, Object> server = objectSchema(linkedMap(
                "serverId", Map.of("type", "string"),
                "displayName", Map.of("type", "string"),
                "environment", Map.of("enum", List.of("development", "test", "staging", "production")),
                "version", Map.of("type", List.of("string", "null"))),
                List.of("serverId", "displayName", "environment"));
        Map<String, Object> output = objectSchema(linkedMap(
                "server", server,
                "status", Map.of("enum", List.of("healthy", "degraded", "unhealthy", "unknown")),
                "checks", Map.of("type", "array", "items", check),
                "observedAt", Map.of("type", "string", "format", "date-time")),
                List.of("server", "status", "checks", "observedAt"));
        return buildSpec(HEALTH_TOOL, "读取服务器健康状态", "返回当前服务端的安全健康摘要",
                "返回当前连接服务端经过安全筛选的 liveness、readiness 和依赖检查摘要。",
                List.of("developer", "operations"), List.of("server", "health", "diagnostics"),
                "read", ApiScopes.SERVER_HEALTH_READ, input, output,
                List.of("server"), "connected_server", "required_compact", "none",
                List.of("game/server-status", "text/markdown"));
    }

    private static ToolSpec onlineSpec() {
        Map<String, Object> input = objectSchema(linkedMap(
                "pageSize", linkedMap("type", "integer", "minimum", 1, "maximum", 200, "default", 100),
                "cursor", linkedMap("type", List.of("string", "null"), "maxLength", 2048)), List.of());
        Map<String, Object> player = objectSchema(linkedMap(
                "characterId", Map.of("type", "string"),
                "name", Map.of("type", "string"),
                "level", linkedMap("type", "integer", "minimum", 0),
                "jobId", linkedMap("type", "integer", "minimum", 0),
                "mapId", linkedMap("type", "integer", "minimum", 0)),
                List.of("characterId", "name", "level", "jobId", "mapId"));
        Map<String, Object> output = objectSchema(linkedMap(
                "serverId", Map.of("type", "string"),
                "snapshotVersion", Map.of("type", "string"),
                "onlineCount", linkedMap("type", "integer", "minimum", 0),
                "returnedCount", linkedMap("type", "integer", "minimum", 0),
                "players", Map.of("type", "array", "items", player),
                "nextCursor", Map.of("type", List.of("string", "null")),
                "observedAt", Map.of("type", "string", "format", "date-time")),
                List.of("serverId", "snapshotVersion", "onlineCount", "returnedCount",
                        "players", "nextCursor", "observedAt"));
        return buildSpec(ONLINE_TOOL, "读取在线玩家列表", "分页返回当前在线角色的安全快照",
                "分页返回当前连接服务端的在线角色安全快照。",
                List.of("developer", "gm", "operations"), List.of("player", "online", "session"),
                "sensitive_read", ApiScopes.PLAYER_ONLINE_READ, input, output,
                List.of("server", "character"), "connected_server_online_characters",
                "required", "page_metadata_only",
                List.of("data/table", "game/character-card", "text/markdown"));
    }

    private static ToolSpec agentInvestigateSpec(boolean available) {
        Map<String, Object> input = objectSchema(linkedMap(
                "conversationId", linkedMap("type", List.of("string", "null"), "maxLength", 64,
                        "pattern", "^[A-Za-z0-9._:-]{1,64}$"),
                "message", linkedMap("type", "string", "minLength", 1, "maxLength", 2000)),
                List.of("message"));
        Map<String, Object> output = objectSchema(linkedMap(
                "conversationId", Map.of("type", "string"),
                "reply", Map.of("type", "string"),
                "model", Map.of("type", "string"),
                "executedTools", Map.of("type", "array", "items", Map.of("type", "string")),
                "auditRefs", Map.of("type", "array", "items", Map.of("type", "string")),
                "inputTokens", linkedMap("type", "integer", "minimum", 0),
                "outputTokens", linkedMap("type", "integer", "minimum", 0)),
                List.of("conversationId", "reply", "model", "executedTools", "auditRefs",
                        "inputTokens", "outputTokens"));
        return buildSpec(AGENT_INVESTIGATE_TOOL, "服务端 Agent 只读调查",
                "让服务端 Agent 自主选择只读取证工具并返回可审计答复",
                "提交自然语言问题；服务端按当前 Subject 隔离会话，并返回工具和审计引用。",
                List.of("developer", "gm", "support"),
                List.of("agent", "ai", "investigation", "audit"),
                "sensitive_read", ApiScopes.AI_USE, input, output,
                List.of("server", "character", "account"), "authorized_server_resources",
                "required", "message_hash_and_length",
                List.of("text/markdown", "application/json"), available, 60000);
    }

    private static ToolSpec agentCloseSpec(boolean available) {
        Map<String, Object> input = objectSchema(linkedMap(
                "conversationId", linkedMap("type", "string", "maxLength", 64,
                        "pattern", "^[A-Za-z0-9._:-]{1,64}$")), List.of("conversationId"));
        Map<String, Object> output = objectSchema(linkedMap(
                "conversationId", Map.of("type", "string"),
                "evicted", Map.of("type", "boolean")), List.of("conversationId", "evicted"));
        return buildSpec(AGENT_CLOSE_TOOL, "关闭服务端 Agent 会话",
                "释放当前 Subject 的指定 Agent 会话记忆",
                "仅释放当前 Subject 命名空间内的会话，不影响其他身份或游戏状态。",
                List.of("developer", "gm", "support"), List.of("agent", "ai", "conversation"),
                "read", ApiScopes.AI_USE, input, output, List.of("server"),
                "subject_conversation", "required_compact", "conversation_id_only",
                List.of("application/json"), available, 5000);
    }

    private static ToolSpec inventorySpec() {
        Map<String, Object> input = objectSchema(linkedMap(
                "characterId", linkedMap("type", "string", "pattern", "^[1-9][0-9]{0,18}$")),
                List.of("characterId"));
        Map<String, Object> equip = objectSchema(linkedMap(
                "upgradeSlots", Map.of("type", "integer"),
                "level", Map.of("type", "integer"),
                "strength", Map.of("type", "integer"),
                "dexterity", Map.of("type", "integer"),
                "intelligence", Map.of("type", "integer"),
                "luck", Map.of("type", "integer"),
                "hp", Map.of("type", "integer"),
                "mp", Map.of("type", "integer"),
                "weaponAttack", Map.of("type", "integer"),
                "magicAttack", Map.of("type", "integer"),
                "weaponDefense", Map.of("type", "integer"),
                "magicDefense", Map.of("type", "integer"),
                "accuracy", Map.of("type", "integer"),
                "avoidability", Map.of("type", "integer"),
                "hands", Map.of("type", "integer"),
                "speed", Map.of("type", "integer"),
                "jump", Map.of("type", "integer"),
                "vicious", Map.of("type", "integer"),
                "itemLevel", Map.of("type", "integer"),
                "itemExp", Map.of("type", "integer"),
                "ringId", Map.of("type", "integer")),
                List.of("upgradeSlots", "level", "strength", "dexterity", "intelligence", "luck",
                        "hp", "mp", "weaponAttack", "magicAttack", "weaponDefense", "magicDefense",
                        "accuracy", "avoidability", "hands", "speed", "jump", "vicious",
                        "itemLevel", "itemExp", "ringId"));
        Map<String, Object> pet = objectSchema(linkedMap(
                "name", Map.of("type", "string"),
                "level", Map.of("type", "integer"),
                "closeness", Map.of("type", "integer"),
                "fullness", Map.of("type", "integer"),
                "attribute", Map.of("type", "integer"),
                "skill", Map.of("type", "integer"),
                "remainLife", Map.of("type", "integer"),
                "itemAttribute", Map.of("type", "integer")),
                List.of("name", "level", "closeness", "fullness", "attribute", "skill",
                        "remainLife", "itemAttribute"));
        Map<String, Object> item = objectSchema(linkedMap(
                "inventoryType", linkedMap("type", "integer", "minimum", 1, "maximum", 5),
                "position", Map.of("type", "integer"),
                "itemType", Map.of("enum", List.of("item", "equip", "pet")),
                "itemId", Map.of("type", "integer"),
                "quantity", linkedMap("type", "integer", "minimum", 1),
                "cashId", Map.of("type", "string"),
                "petId", Map.of("type", "string"),
                "owner", Map.of("type", "string"),
                "flag", Map.of("type", "integer"),
                "expiration", Map.of("type", "integer"),
                "equip", equip,
                "pet", pet),
                List.of("inventoryType", "position", "itemType", "itemId", "quantity",
                        "cashId", "petId", "owner", "flag", "expiration"));
        Map<String, Object> output = objectSchema(linkedMap(
                "serverId", Map.of("type", "string"),
                "characterId", Map.of("type", "string"),
                "name", Map.of("type", "string"),
                "stateVersion", Map.of("type", "string"),
                "itemCount", linkedMap("type", "integer", "minimum", 0),
                "items", Map.of("type", "array", "items", item),
                "observedAt", Map.of("type", "string", "format", "date-time")),
                List.of("serverId", "characterId", "name", "stateVersion", "itemCount",
                        "items", "observedAt"));
        return buildSpec(INVENTORY_TOOL, "读取在线角色背包", "返回频道内存中的精确背包快照",
                "读取在线角色尚未必然落盘的五类背包、装备扩展和宠物实例状态。",
                List.of("developer", "gm", "operations"),
                List.of("player", "inventory", "incident", "item"),
                "sensitive_read", ApiScopes.PLAYER_INVENTORY_READ, input, output,
                List.of("server", "character", "inventory"), "online_character_id",
                "required", "characterId", List.of("data/table", "text/markdown"));
    }

    private static ToolSpec buildSpec(String toolId, String title, String summary, String description,
                                      List<String> categories, List<String> tags, String riskLevel,
                                      String requiredScope, Map<String, Object> input,
                                      Map<String, Object> output, List<String> resourceTypes,
                                      String resourceResolution, String auditMode,
                                      String parameterSummary, List<String> contentTypes) {
        return buildSpec(toolId, title, summary, description, categories, tags, riskLevel,
                requiredScope, input, output, resourceTypes, resourceResolution, auditMode,
                parameterSummary, contentTypes, true, 5000);
    }

    private static ToolSpec buildSpec(String toolId, String title, String summary, String description,
                                      List<String> categories, List<String> tags, String riskLevel,
                                      String requiredScope, Map<String, Object> input,
                                      Map<String, Object> output, List<String> resourceTypes,
                                      String resourceResolution, String auditMode,
                                      String parameterSummary, List<String> contentTypes,
                                      boolean available, int timeoutMs) {
        LinkedHashMap<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("toolId", toolId);
        summaryMap.put("toolVersion", TOOL_VERSION);
        summaryMap.put("title", title);
        summaryMap.put("summary", summary);
        summaryMap.put("provider", "server");
        summaryMap.put("categories", categories);
        summaryMap.put("tags", tags);
        summaryMap.put("riskLevel", riskLevel);
        summaryMap.put("availability", available ? "available" : "unavailable");
        summaryMap.put("permissionState", "allowed");

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("contractVersion", ApiContract.VERSION);
        detail.put("toolId", toolId);
        detail.put("toolVersion", TOOL_VERSION);
        detail.put("title", title);
        detail.put("description", description);
        detail.put("provider", "server");
        detail.put("availability", available ? "available" : "unavailable");
        detail.put("schemaDialect", "https://json-schema.org/draft/2020-12/schema");
        detail.put("inputSchema", input);
        detail.put("outputSchema", output);
        detail.put("permission", linkedMap(
                "requiredScopes", List.of(requiredScope),
                "resourceTypes", resourceTypes,
                "resourceResolution", resourceResolution));
        detail.put("risk", linkedMap(
                "level", riskLevel, "confirmation", "never", "supportsDryRun", false));
        detail.put("execution", linkedMap(
                "mode", "sync", "timeoutMs", timeoutMs,
                "idempotency", "not_required", "retryPolicy", "safe_read_backoff"));
        detail.put("result", linkedMap(
                "contentTypes", contentTypes, "dataClassification", "internal"));
        detail.put("audit", linkedMap("mode", auditMode, "parameterSummary", parameterSummary));
        return new ToolSpec(toolId, TOOL_VERSION, title, summary, tags, requiredScope, available,
                Map.copyOf(summaryMap), Map.copyOf(detail));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties,
                                                    List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static LinkedHashMap<String, Object> linkedMap(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    public record ToolSpec(String toolId, String toolVersion, String title, String summaryText,
                           List<String> tags, String requiredScope, boolean available,
                           Map<String, Object> summary, Map<String, Object> detail) {
    }
}
