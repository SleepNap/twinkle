package org.gms.data.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * newmaple 老库 → twinkle 新库一次性存档导入工具（架构 M5-2 单库迁移）。
 *
 * <p><b>设计</b>：migration 管结构、seed 管内容——目标库先由 {@code MigrationRunner} 跑 V1-V7
 * 建好结构，本工具只做内容拷贝。输入两个 {@link DataSource}（源 = newmaple，目标 = twinkle）。
 *
 * <p><b>复制原则</b>：
 * <ul>
 *   <li>显式列清单 + 显式主键（保 ID 外键完整），严禁 {@code SELECT * → INSERT *} 依赖列顺序。</li>
 *   <li>拷贝顺序按外键依赖：accounts → characters → queststatus → questprogress → inventoryitems。</li>
 *   <li>值语义差异（inventoryitems 的 characterid/accountid 源可 NULL → 目标 COALESCE 0）在列配置中声明。</li>
 *   <li>密码哈希：源老库非 BCrypt（如 SHA512 前缀），直接导入无法登录；{@code resetPasswords} 开启时
 *       对非 {@code $2} 前缀哈希用调用方传入的 rehasher 替换（CLI 传 BCrypt 实现，本模块不依赖 jbcrypt）。</li>
 *   <li>newmaple {@code buddies} 与 twinkle {@code buddylist} 结构不兼容（红线 2），导入时跳过并计数。</li>
 * </ul>
 *
 * <p><b>方言</b>：源 SELECT 用反引号标识符（MySQL/SQLite 均接受）；目标 INSERT 标识符按目标方言
 * 引用（sqlite/mysql 反引号，postgresql 双引号）——避免 {@code int}/{@code "int"} 等保留字列名歧义。
 */
public final class NewMapleImporter {

    private static final Logger LOG = LogManager.getLogger(NewMapleImporter.class);

    private static final String T_ACCOUNTS = "accounts";
    private static final String T_CHARACTERS = "characters";
    private static final String T_QUESTSTATUS = "queststatus";
    private static final String T_QUESTPROGRESS = "questprogress";
    private static final String T_INVENTORY = "inventoryitems";

    /** 与 newmaple 结构兼容的 74 列（红线 3：characters 存档结构不变）。 */
    private static final String[] CHARACTERS_COLS = {
            "id", "accountid", "world", "name", "level", "exp", "gachaexp", "str", "dex", "luk", "int",
            "hp", "mp", "maxhp", "maxmp", "meso", "hpMpUsed", "job", "skincolor", "gender", "fame",
            "fquest", "hair", "face", "ap", "sp", "map", "spawnpoint", "gm", "party", "buddyCapacity",
            "createdate", "rank", "rankMove", "jobRank", "jobRankMove", "guildid", "guildrank",
            "messengerid", "messengerposition", "mountlevel", "mountexp", "mounttiredness", "omokwins",
            "omoklosses", "omokties", "matchcardwins", "matchcardlosses", "matchcardties", "MerchantMesos",
            "HasMerchant", "equipslots", "useslots", "setupslots", "etcslots", "familyId",
            "monsterbookcover", "allianceRank", "vanquisherStage", "ariantPoints", "dojoPoints",
            "lastDojoStage", "finishedDojoTutorial", "vanquisherKills", "summonValue", "partnerId",
            "marriageItemId", "reborns", "PQPoints", "dataString", "lastLogoutTime", "lastExpGainTime",
            "partySearch", "jailexpire"
    };

    /** queststatus 9 列（V7 补列后与 newmaple 对齐）。 */
    private static final String[] QUESTSTATUS_COLS = {
            "queststatusid", "characterid", "quest", "status", "time",
            "expires", "forfeited", "completed", "info"
    };

    private static final String[] QUESTPROGRESS_COLS = {
            "id", "characterid", "queststatusid", "progressid", "progress"
    };

    /** inventoryitems 13 列；characterid/accountid 源可 NULL → COALESCE 0。 */
    private static final String[] INVENTORY_COLS = {
            "inventoryitemid", "type", "characterid", "accountid", "itemid", "inventorytype",
            "position", "quantity", "owner", "petid", "flag", "expiration", "giftFrom"
    };
    private static final Set<String> INVENTORY_COALESCE = Set.of("characterid", "accountid");

    /** 拷贝选项。 */
    public record CopyOptions(
            /** 是否重置非 BCrypt 老密码为 rehasher 结果（默认建议 true，避免老账号登不上）。 */
            boolean resetPasswords,
            /** 密码重哈希函数（如 {@code s -> BCrypt.hashpw("changeMe123!", BCrypt.gensalt())}）。 */
            Function<String, String> passwordRehasher,
            /** 目标库已有数据时是否先清空（幂等导入）。false 且有数据则拒绝。 */
            boolean truncateTarget) {
        public static CopyOptions defaults(Function<String, String> rehasher) {
            return new CopyOptions(true, rehasher, false);
        }
    }

    /** 拷贝报告。 */
    public record CopyReport(
            int accountsCopied, int charactersCopied, int questStatusCopied,
            int questProgressCopied, int inventoryCopied,
            /** 被跳过的好友关系条数（buddies 结构不兼容，红线 2）。 */
            int buddiesSkipped,
            /** 被重置密码的账号名（老库非 BCrypt 哈希）。 */
            List<String> passwordResetAccounts) {
    }

    private final DataSource source;
    private final DataSource target;
    private final String targetDialect; // "sqlite" / "postgresql" / "mysql"

    private NewMapleImporter(DataSource source, DataSource target, String targetDialect) {
        this.source = source;
        this.target = target;
        this.targetDialect = targetDialect;
    }

    /**
     * 执行拷贝。
     *
     * @param targetDialect 目标库方言 id（sqlite/postgresql/mysql，与 DbDialect.DialectId.name() 小写一致）
     */
    public static CopyReport copy(DataSource source, DataSource target, String targetDialect, CopyOptions opts)
            throws SQLException {
        return new NewMapleImporter(source, target, targetDialect).doCopy(opts);
    }

    private CopyReport doCopy(CopyOptions opts) throws SQLException {
        try (Connection src = source.getConnection();
             Connection tgt = target.getConnection()) {
            tgt.setAutoCommit(false);
            ensureEmptyOrTruncate(tgt, opts.truncateTarget);

            int accounts = copyAccounts(src, tgt, opts);
            int characters = copyTable(src, tgt, T_CHARACTERS, CHARACTERS_COLS, Set.of());
            int questStatus = copyTable(src, tgt, T_QUESTSTATUS, QUESTSTATUS_COLS, Set.of());
            int questProgress = copyTable(src, tgt, T_QUESTPROGRESS, QUESTPROGRESS_COLS, Set.of());
            int inventory = copyTable(src, tgt, T_INVENTORY, INVENTORY_COLS, INVENTORY_COALESCE);
            int buddiesSkipped = countAndSkipBuddies(src);

            tgt.commit();
            LOG.info("导入完成：accounts={} characters={} queststatus={} questprogress={} inventory={}，"
                            + "跳过 buddies={}",
                    accounts, characters, questStatus, questProgress, inventory, buddiesSkipped);
            return new CopyReport(accounts, characters, questStatus, questProgress, inventory,
                    buddiesSkipped, List.of());
        }
    }

    /** 目标库已有数据则拒绝（幂等），truncate 则按依赖序清空。 */
    private static void ensureEmptyOrTruncate(Connection tgt, boolean truncate) throws SQLException {
        if (truncate) {
            for (String table : new String[]{T_INVENTORY, T_QUESTPROGRESS, T_QUESTSTATUS, T_CHARACTERS, T_ACCOUNTS}) {
                try (Statement st = tgt.createStatement()) {
                    st.execute("DELETE FROM " + table);
                }
            }
            LOG.info("目标库已按 --truncate 清空 5 张存档表");
            return;
        }
        try (Statement st = tgt.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + T_ACCOUNTS)) {
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new IllegalStateException("目标库 accounts 已有数据，拒绝覆盖导入（幂等保护）。"
                        + " 如确认要重导，请清空目标库或使用 --truncate");
            }
        }
    }

    /** accounts：30 列 + 密码哈希处理。 */
    private int copyAccounts(Connection src, Connection tgt, CopyOptions opts) throws SQLException {
        String[] cols = {
                "id", "name", "password", "pin", "pic", "loggedin", "lastlogin", "createdat", "birthday",
                "banned", "banreason", "macs", "nxCredit", "maplePoint", "nxPrepaid", "characterslots",
                "gender", "tempban", "greason", "tos", "sitelogged", "webadmin", "nick", "mute", "email",
                "ip", "rewardpoints", "votepoints", "hwid", "language"
        };
        List<String> resetAccounts = new ArrayList<>();
        String select = "SELECT " + joinedQuoted(cols, true) + " FROM " + q(T_ACCOUNTS, true);
        String insert = "INSERT INTO " + q(T_ACCOUNTS, false)
                + " (" + joinedQuoted(cols, false) + ") VALUES (" + placeholders(cols.length) + ")";
        int count = 0;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = tgt.prepareStatement(insert)) {
            while (rs.next()) {
                int p = 1;
                for (String col : cols) {
                    if (col.equals("password") && opts.resetPasswords) {
                        String raw = rs.getString("password");
                        if (raw != null && !raw.startsWith("$2") && opts.passwordRehasher != null) {
                            ps.setString(p, opts.passwordRehasher.apply(raw));
                            resetAccounts.add(rs.getString("name"));
                        } else {
                            ps.setString(p, raw);
                        }
                    } else {
                        ps.setObject(p, rs.getObject(col));
                    }
                    p++;
                }
                ps.addBatch();
                count++;
                if (count % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        if (!resetAccounts.isEmpty()) {
            LOG.warn("已重置 {} 个老库账号密码（非 BCrypt，改为默认口令，清单见切换文档）：{}",
                    resetAccounts.size(), resetAccounts);
        }
        return count;
    }

    /** 通用表拷贝：显式列清单，可 COALESCE 列（源 NULL → 0）。 */
    private int copyTable(Connection src, Connection tgt, String table, String[] cols, Set<String> coalesce)
            throws SQLException {
        String select = "SELECT " + selectExpressions(cols, coalesce) + " FROM " + q(table, true);
        String insert = "INSERT INTO " + q(table, false)
                + " (" + joinedQuoted(cols, false) + ") VALUES (" + placeholders(cols.length) + ")";
        int count = 0;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = tgt.prepareStatement(insert)) {
            while (rs.next()) {
                // 按列索引取值（SELECT 表达式顺序 = cols 数组顺序）——COALESCE 表达式的列名
                // 在 SQLite 里不保留原名（如 `characterid` 变成 COALESCE(...)），不能按名取。
                for (int i = 0; i < cols.length; i++) {
                    ps.setObject(i + 1, rs.getObject(i + 1));
                }
                ps.addBatch();
                count++;
                if (count % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        LOG.info("表 {} 拷贝 {} 行", table, count);
        return count;
    }

    /** newmaple buddies 与 twinkle buddylist 结构不兼容，跳过并计数（记录已知差异）。 */
    private static int countAndSkipBuddies(Connection src) throws SQLException {
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM buddies")) {
            rs.next();
            int n = rs.getInt(1);
            if (n > 0) {
                LOG.warn("跳过 {} 条 newmaple buddies（结构与 twinkle buddylist 不兼容，好友关系需游戏中重建）", n);
            }
            return n;
        }
    }

    // ---- 标识符引用与 SQL 拼装 ----

    /** 目标方言标识符引用：sqlite/mysql 反引号，postgresql 双引号。 */
    private String q(String id, boolean sourceSide) {
        if (!sourceSide && "postgresql".equals(targetDialect)) {
            return "\"" + id + "\"";
        }
        return "`" + id + "`";
    }

    /** 列名列表拼接（含引号）。 */
    private String joinedQuoted(String[] cols, boolean sourceSide) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(q(cols[i], sourceSide));
        }
        return sb.toString();
    }

    /** SELECT 表达式：COALESCE 列用 {@code COALESCE(\`col\`, 0)}。 */
    private String selectExpressions(String[] cols, Set<String> coalesce) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String quoted = q(cols[i], true);
            sb.append(coalesce.contains(cols[i]) ? "COALESCE(" + quoted + ", 0)" : quoted);
        }
        return sb.toString();
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
