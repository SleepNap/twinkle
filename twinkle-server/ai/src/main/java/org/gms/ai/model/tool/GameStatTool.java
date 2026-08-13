package org.gms.ai.model.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.invocation.InvocationParameters;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.i18n.I18n;
import org.gms.observability.Metrics;
import org.gms.service.admin.AdminService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * AI 工具集（架构 M3-2：AI 工具不直踩游戏内存对象，只经 application service 接口）。
 *
 * <p>在线统计：经 {@link AdminService}（core 公共契约，单进程直调/分进程 RPC，接口不变）
 * 取在线快照，只读 DTO——不触碰频道内存对象。埋点经 {@link Metrics}。
 *
 * <p>{@code @Tool} 注解由 LangChain4j 扫描注册为 AiServices 可调用工具；本地规则模型
 * 触发、真实 LLM 亦可原生调用（换模型即通）。
 */
public final class GameStatTool {

    private final AdminService adminService;
    private final AccountRepository accountRepository;
    private final CharacterRepository characterRepository;
    private final InventoryItemRepository inventoryRepository;
    private final Metrics metrics;
    private final AgentToolAudit audit;

    public GameStatTool(AdminService adminService, AccountRepository accountRepository,
                        CharacterRepository characterRepository, InventoryItemRepository inventoryRepository,
                        Metrics metrics, AgentToolAudit audit) {
        this.adminService = adminService;
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
        this.inventoryRepository = inventoryRepository;
        this.metrics = metrics;
        this.audit = audit;
    }

    /** 查询在线玩家统计（人数 + 按地图分组 + 在线玩家名）。 */
    @Tool("查询当前在线玩家统计：总在线人数、各地图人数、在线玩家名单")
    public String queryOnlineStats(InvocationParameters parameters) {
        return audit.execute("gm.online.stats.read", "none", "查询在线概览", parameters, () -> {
            metrics.increment("ai.tool.online_stats");
            AdminService.ChannelSummary summary = adminService.onlineSummary();
            List<AdminService.OnlinePlayer> players = summary.players();
            if (players.isEmpty()) {
                return "当前在线人数为 0。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("当前在线总人数：").append(players.size()).append("\n");
            if (isPlayerChat(parameters)) {
                players.stream().collect(java.util.stream.Collectors.groupingBy(
                                AdminService.OnlinePlayer::mapId, java.util.TreeMap::new,
                                java.util.stream.Collectors.counting()))
                        .forEach((mapId, count) -> sb.append("  地图 ").append(mapId)
                                .append("：").append(count).append(" 人\n"));
                return sb.toString();
            }
            sb.append("在线玩家：\n");
            for (AdminService.OnlinePlayer p : players) {
                sb.append("  - ").append(p.name()).append("（角色ID ").append(p.characterId())
                        .append("，等级 ").append(p.level()).append("，地图 ").append(p.mapId()).append("）\n");
            }
            return sb.toString();
        });
    }

    /** 查询角色存档概况，并与在线只读快照交叉验证。 */
    @Tool("按角色名查询角色存档概况：角色ID、等级、职业、地图、金币、最近退出时间和当前是否在线")
    public String queryPlayerProfile(@P("精确角色名") String characterName,
                                     InvocationParameters parameters) {
        String safeName = validatedName(characterName);
        return audit.execute("gm.player.profile.read",
                AgentToolAudit.sensitiveKeySummary("characterName", safeName),
                "查询角色存档概况", parameters, () -> {
                    metrics.increment("ai.tool.player_profile");
                    Optional<Character> found = findAuthorizedCharacter(safeName, parameters);
                    if (found.isEmpty()) {
                        return "没有找到角色「" + safeName + "」。";
                    }
                    Character chr = found.get();
                    Optional<AdminService.OnlinePlayer> online = adminService.onlineSummary().players().stream()
                            .filter(player -> player.characterId() == chr.getId())
                            .findFirst();
                    return "角色「" + chr.getName() + "」：角色ID=" + chr.getId()
                            + "，等级=" + chr.getLevel() + "，职业=" + chr.getJob()
                            + "，存档地图=" + chr.getMap() + "，金币=" + chr.getMeso()
                            + "，最近退出=" + nullable(chr.getLastLogoutTime())
                            + "，当前在线=" + online.isPresent()
                            + online.map(player -> "，在线地图=" + player.mapId()).orElse("") + "。";
                });
    }

    /** 查询角色当前已落库背包快照；不声称覆盖尚未落盘的内存变更。 */
    @Tool("按角色名查询已落库背包快照，返回物品ID、分类、槽位和数量；用于调查物品是否仍在背包")
    public String queryPlayerInventory(@P("精确角色名") String characterName,
                                       InvocationParameters parameters) {
        String safeName = validatedName(characterName);
        return audit.execute("gm.player.inventory.read",
                AgentToolAudit.sensitiveKeySummary("characterName", safeName),
                "查询角色已落库背包", parameters, () -> {
                    metrics.increment("ai.tool.player_inventory");
                    Optional<Character> found = findAuthorizedCharacter(safeName, parameters);
                    if (found.isEmpty()) {
                        return "没有找到角色「" + safeName + "」。";
                    }
                    Character chr = found.get();
                    List<InventoryItemEntity> items = inventoryRepository.findByCharacterId(chr.getId()).stream()
                            .sorted(Comparator.comparingInt(InventoryItemEntity::getInventoryType)
                                    .thenComparingInt(InventoryItemEntity::getPosition))
                            .limit(200)
                            .toList();
                    if (items.isEmpty()) {
                        return "角色「" + chr.getName() + "」的已落库背包为空。注意：在线角色尚未落盘的变更不在此快照内。";
                    }
                    StringBuilder sb = new StringBuilder("角色「").append(chr.getName())
                            .append("」已落库背包（最多 200 条；在线未落盘变更不在内）：\n");
                    for (InventoryItemEntity item : items) {
                        sb.append("- itemId=").append(item.getItemId())
                                .append("，分类=").append(item.getInventoryType())
                                .append("，槽位=").append(item.getPosition())
                                .append("，数量=").append(item.getQuantity())
                                .append("，到期=").append(item.getExpiration()).append("\n");
                    }
                    return sb.toString();
                });
    }

    /** 查询账号存在、封禁和 GM 状态；不返回密码摘要等凭据信息。 */
    @Tool("按账号名查询账号状态：账号ID、是否封禁和Web管理标志；绝不返回密码或Credential")
    public String queryAccountStatus(@P("精确账号名") String name, InvocationParameters parameters) {
        String safeName = validatedName(name);
        return audit.execute("gm.account.status.read",
                AgentToolAudit.sensitiveKeySummary("accountName", safeName),
                "查询账号安全状态", parameters, () -> {
                    requireManagementSource(parameters);
                    metrics.increment("ai.tool.account_status");
                    Optional<Account> account = accountRepository.findByName(safeName);
                    return account.map(value -> "账号「" + value.getName() + "」存在，id=" + value.getId()
                                    + "，封禁=" + (value.getBanned() == 1)
                                    + "，Web管理标志=" + nullableInteger(value.getWebAdmin()) + "。")
                            .orElse("账号「" + safeName + "」不存在。");
                });
    }

    /** 玩家聊天只可读取本人角色；管理 API 才允许按角色名调查。 */
    private Optional<Character> findAuthorizedCharacter(String requestedName,
                                                        InvocationParameters parameters) {
        if (!isPlayerChat(parameters)) {
            return characterRepository.findByName(requestedName);
        }
        long characterId = playerCharacterId(parameters);
        Optional<Character> ownCharacter = characterRepository.findById(characterId);
        if (ownCharacter.isEmpty() || !ownCharacter.get().getName().equalsIgnoreCase(requestedName)) {
            throw new SecurityException(I18n.message("error.ai.player_chat_self_only"));
        }
        return ownCharacter;
    }

    private static void requireManagementSource(InvocationParameters parameters) {
        if (isPlayerChat(parameters)) {
            throw new SecurityException(I18n.message("error.ai.player_chat_account_denied"));
        }
    }

    private static boolean isPlayerChat(InvocationParameters parameters) {
        Object source = parameters.get("source");
        return source != null && "game-chat".equals(source.toString());
    }

    private static long playerCharacterId(InvocationParameters parameters) {
        Object subject = parameters.get("subjectId");
        String value = subject == null ? "" : subject.toString();
        if (!value.matches("player:[0-9]+")) {
            throw new SecurityException(I18n.message("error.ai.player_context_invalid"));
        }
        return Long.parseLong(value.substring("player:".length()));
    }

    private static String validatedName(String name) {
        if (name == null || !name.trim().matches("[A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,32}")) {
            throw new IllegalArgumentException(I18n.message("error.ai.name_format_invalid"));
        }
        return name.trim();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private static String nullableInteger(Integer value) {
        return value == null ? "未知" : value.toString();
    }
}
