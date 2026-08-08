package org.gms.ai.model.tool;

import dev.langchain4j.agent.tool.Tool;
import org.gms.data.entity.Account;
import org.gms.data.repo.AccountRepository;
import org.gms.observability.Metrics;
import org.gms.service.admin.AdminService;

import java.util.List;

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
    private final Metrics metrics;

    public GameStatTool(AdminService adminService, AccountRepository accountRepository, Metrics metrics) {
        this.adminService = adminService;
        this.accountRepository = accountRepository;
        this.metrics = metrics;
    }

    /** 查询在线玩家统计（人数 + 按地图分组 + 在线玩家名）。 */
    @Tool("查询当前在线玩家统计：总在线人数、各地图人数、在线玩家名单")
    public String queryOnlineStats() {
        metrics.increment("ai.tool.online_stats");
        AdminService.ChannelSummary summary = adminService.onlineSummary();
        List<AdminService.OnlinePlayer> players = summary.players();
        if (players.isEmpty()) {
            return "当前在线人数为 0。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前在线总人数：").append(players.size()).append("\n");
        sb.append("在线玩家：\n");
        for (AdminService.OnlinePlayer p : players) {
            sb.append("  - ").append(p.name()).append("（等级").append(p.level())
                    .append("，地图 ").append(p.mapId()).append("）\n");
        }
        return sb.toString();
    }

    /** 查询账号是否存在（示例第二工具：多工具调用演示）。 */
    @Tool("查询指定账号是否存在，参数为账号名")
    public String accountExists(String name) {
        metrics.increment("ai.tool.account_exists");
        java.util.Optional<Account> acc = accountRepository.findByName(name);
        return acc.map(a -> "账号「" + a.getName() + "」存在，id=" + a.getId() + "，封禁=" + (a.getBanned() == 1))
                .orElse("账号「" + name + "」不存在");
    }
}
