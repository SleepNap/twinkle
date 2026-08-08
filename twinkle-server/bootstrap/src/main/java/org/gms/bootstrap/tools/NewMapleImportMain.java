package org.gms.bootstrap.tools;

import org.gms.data.SimpleDriverDataSource;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.tools.NewMapleImporter;
import org.mindrot.jbcrypt.BCrypt;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * newmaple 老库 → twinkle 新库一次性导入 CLI（架构 M5-2 单库迁移）。
 *
 * <p>用法：
 * <pre>{@code
 * java -cp twinkle.jar org.gms.bootstrap.tools.NewMapleImportMain \
 *   --source-url=jdbc:mysql://host:3306/newmaple --source-user=root --source-pass=xxx \
 *   --target-url=jdbc:sqlite:./data/twinkle.db \
 *   [--truncate] [--no-reset-passwords]
 * }</pre>
 *
 * <p>流程：目标库先跑 V1-V7 迁移建结构（migration 管结构）→ 拷贝内容（seed 管内容）→ 校验。
 * 默认 {@code --reset-passwords}：老库非 BCrypt 哈希重置为 BCrypt(默认口令 changeMe123!)，避免老账号
 * 导入后无法登录（twinkle 登录用 BCrypt.checkpw）。重置清单打印到日志，落档见切换文档已知差异。
 *
 * <p>退出码：0 成功；非 0 失败（含目标库已有数据且未 --truncate 的幂等保护）。
 */
public final class NewMapleImportMain {

    private static final String DEFAULT_PASSWORD = "changeMe123!";

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        String sourceUrl = require(opts, "--source-url");
        String sourceUser = opts.getOrDefault("--source-user", "");
        String sourcePass = opts.getOrDefault("--source-pass", "");
        String targetUrl = require(opts, "--target-url");
        String targetUser = opts.getOrDefault("--target-user", "");
        String targetPass = opts.getOrDefault("--target-pass", "");
        boolean truncate = opts.containsKey("--truncate");
        boolean resetPasswords = !opts.containsKey("--no-reset-passwords");

        // 方言：DbDialectRegistry 由 Micronaut DI 注入方言列表，CLI 不在容器内——直接用 URL 前缀判定
        //（与 DbDialectRegistry.resolveByUrl 内部逻辑一致：sqlite/postgresql/mysql，未知回落 sqlite）。
        String targetDialect = dialectByUrl(targetUrl);

        DataSource source = new SimpleDriverDataSource(sourceUrl, sourceUser, sourcePass);
        DataSource target = new SimpleDriverDataSource(targetUrl, targetUser, targetPass);

        // 目标库先建结构（V1-V7 全量迁移；若已跑过则跳过已应用版本）
        MigrationRunner.applyMigrations(target, targetDialect);

        NewMapleImporter.CopyOptions optsObj = new NewMapleImporter.CopyOptions(
                resetPasswords,
                resetPasswords ? (old -> BCrypt.hashpw(DEFAULT_PASSWORD, BCrypt.gensalt())) : null,
                truncate);

        NewMapleImporter.CopyReport report = NewMapleImporter.copy(source, target, targetDialect, optsObj);

        System.out.println("导入完成：");
        System.out.println("  accounts=" + report.accountsCopied()
                + " characters=" + report.charactersCopied()
                + " queststatus=" + report.questStatusCopied()
                + " questprogress=" + report.questProgressCopied()
                + " inventory=" + report.inventoryCopied());
        System.out.println("  跳过 buddies=" + report.buddiesSkipped()
                + "（结构不兼容，好友关系需游戏中重建）");
        if (resetPasswords && !report.passwordResetAccounts().isEmpty()) {
            System.out.println("  已重置 " + report.passwordResetAccounts().size()
                    + " 个老库账号密码为默认口令 " + DEFAULT_PASSWORD + "（清单见切换文档）");
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int eq = arg.indexOf('=');
                map.put(arg.substring(0, eq), arg.substring(eq + 1));
            } else if (arg.startsWith("--")) {
                map.put(arg, "true"); // 布尔开关：--truncate / --no-reset-passwords
            }
        }
        return map;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return value;
    }

    /** 按 JDBC URL 前缀判定方言 id（与 DbDialectRegistry.resolveByUrl 一致，未知回落 sqlite）。 */
    private static String dialectByUrl(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return "mysql";
        }
        return "sqlite";
    }

    private NewMapleImportMain() {
    }
}
