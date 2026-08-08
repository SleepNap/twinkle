package org.gms.data.tools;

import org.gms.data.SimpleDriverDataSource;
import org.gms.data.migrate.MigrationRunner;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-2 单库迁移：newmaple 老库 → twinkle 新库复制引擎测试（架构 M5-2 第 2 节）。
 *
 * <p>两个临时 SQLite：源按 newmaple 老结构手写 DDL（queststatus 9 列、inventoryitems 可 NULL
 * characterid/accountid、petid -1、buddies 表），灌样例行；目标空库跑 V1-V7 迁移后 copy。
 * 验证：四表行数一致、queststatus 新列值保留、inventoryitems NULL → 0、buddies 跳过、幂等保护。
 */
class NewMapleImporterTest {

    @Test
    void copyTransfersNewmapleDataWithValueSemantics() throws Exception {
        String sourcePath = Files.createTempDirectory("twinkle-src").resolve("old.db").toString();
        String targetPath = Files.createTempDirectory("twinkle-dst").resolve("new.db").toString();
        DataSource source = new SimpleDriverDataSource("jdbc:sqlite:" + sourcePath, "", "");
        DataSource target = new SimpleDriverDataSource("jdbc:sqlite:" + targetPath, "", "");

        createNewmapleSchema(source);
        seedNewmapleData(source);

        // 目标库先跑迁移建结构（migration 管结构）
        MigrationRunner.applyMigrations(target, "sqlite");

        // 复制（seed 管内容）；密码重置 rehasher 标记 —— 这里验证老格式密码被替换
        NewMapleImporter.CopyReport report = NewMapleImporter.copy(source, target, "sqlite",
                NewMapleImporter.CopyOptions.defaults(old -> "{RESET}" + old));

        // ---- 行数 ----
        assertThat(report.accountsCopied()).isEqualTo(2);
        assertThat(report.charactersCopied()).isEqualTo(2);
        assertThat(report.questStatusCopied()).isEqualTo(2);
        assertThat(report.questProgressCopied()).isEqualTo(2);
        assertThat(report.inventoryCopied()).isEqualTo(2);
        assertThat(report.buddiesSkipped()).isEqualTo(1);

        // ---- accounts：老格式密码被重置，BCrypt 格式保留 ----
        try (Connection c = target.getConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT name, password FROM accounts ORDER BY id")) {
                rs.next();
                assertThat(rs.getString("name")).isEqualTo("admin");
                assertThat(rs.getString("password")).startsWith("{RESET}"); // 老 SHA512 → 重置
                rs.next();
                assertThat(rs.getString("name")).isEqualTo("newbie");
                assertThat(rs.getString("password")).isEqualTo("$2a$10$kept"); // BCrypt 保留
            }
        }

        // ---- characters：74 列标量抽查 ----
        try (Connection c = target.getConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT name, level, meso, map, job FROM characters WHERE id=1")) {
                rs.next();
                assertThat(rs.getString("name")).isEqualTo("Hero");
                assertThat(rs.getInt("level")).isEqualTo(10);
                assertThat(rs.getInt("meso")).isEqualTo(5000);
                assertThat(rs.getInt("map")).isEqualTo(100000000);
            }
        }

        // ---- queststatus：V7 补列后新列值保留 ----
        try (Connection c = target.getConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT quest, expires, forfeited, completed, info FROM queststatus WHERE queststatusid=1")) {
                rs.next();
                assertThat(rs.getInt("quest")).isEqualTo(1000);
                assertThat(rs.getLong("expires")).isEqualTo(123456789L);
                assertThat(rs.getInt("forfeited")).isEqualTo(0);
                assertThat(rs.getInt("completed")).isEqualTo(1);
                assertThat(rs.getInt("info")).isEqualTo(1);
            }
        }

        // ---- inventoryitems：NULL characterid/accountid → 0（COALESCE 语义）----
        try (Connection c = target.getConnection();
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT itemid, characterid, accountid, petid FROM inventoryitems WHERE inventoryitemid=1")) {
                rs.next();
                assertThat(rs.getInt("itemid")).isEqualTo(4000000);
                assertThat(rs.getInt("characterid")).isEqualTo(0); // 源 NULL → 0
                assertThat(rs.getInt("accountid")).isEqualTo(0);
                assertThat(rs.getInt("petid")).isEqualTo(-1); // 老库默认 -1 保留
            }
        }

        // ---- buddies 未写入目标 ----
        try (Connection c = target.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM buddylist")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void rejectsNonEmptyTargetUnlessTruncate() throws Exception {
        String sourcePath = Files.createTempDirectory("twinkle-src2").resolve("old.db").toString();
        String targetPath = Files.createTempDirectory("twinkle-dst2").resolve("new.db").toString();
        DataSource source = new SimpleDriverDataSource("jdbc:sqlite:" + sourcePath, "", "");
        DataSource target = new SimpleDriverDataSource("jdbc:sqlite:" + targetPath, "", "");

        createNewmapleSchema(source);
        seedNewmapleData(source);
        MigrationRunner.applyMigrations(target, "sqlite");

        // 目标库已有数据（迁移 seed 的 param_conf 不算，但先手动塞一条 accounts）
        try (Connection c = target.getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO accounts (name, password) VALUES ('existing', 'x')");
        }

        // 幂等保护：非 truncate 拒绝
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> NewMapleImporter.copy(source, target, "sqlite",
                        NewMapleImporter.CopyOptions.defaults(null)));

        // truncate 后允许重导
        NewMapleImporter.CopyReport report = NewMapleImporter.copy(source, target, "sqlite",
                new NewMapleImporter.CopyOptions(false, null, true));
        assertThat(report.accountsCopied()).isEqualTo(2);
    }

    /** 按 newmaple 老结构建源库 DDL（queststatus 9 列、inventoryitems 可 NULL、buddies）。 */
    private static void createNewmapleSchema(DataSource source) throws Exception {
        try (Connection c = source.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts ("
                    + "id INTEGER PRIMARY KEY, name VARCHAR(13) NOT NULL DEFAULT '', password VARCHAR(128) NOT NULL DEFAULT '',"
                    + "loggedin INTEGER NOT NULL DEFAULT 0, banned INTEGER NOT NULL DEFAULT 0, characterslots INTEGER NOT NULL DEFAULT 3,"
                    + "gender INTEGER NOT NULL DEFAULT 10, birthday TEXT NOT NULL DEFAULT '2005-05-11',"
                    + "tempban TEXT NOT NULL DEFAULT '2005-05-11 00:00:00', language INTEGER NOT NULL DEFAULT 3,"
                    + "pin VARCHAR(10) NOT NULL DEFAULT '', pic VARCHAR(26) NOT NULL DEFAULT '', createdat TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "lastlogin TEXT, banreason TEXT, macs TEXT, nxCredit INTEGER, maplePoint INTEGER, nxPrepaid INTEGER,"
                    + "greason INTEGER NOT NULL DEFAULT 0, tos INTEGER NOT NULL DEFAULT 0, sitelogged TEXT, webadmin INTEGER,"
                    + "nick TEXT, mute INTEGER, email TEXT, ip TEXT, rewardpoints INTEGER NOT NULL DEFAULT 0,"
                    + "votepoints INTEGER NOT NULL DEFAULT 0, hwid TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE characters ("
                    + "id INTEGER PRIMARY KEY, accountid INTEGER NOT NULL DEFAULT 0, world INTEGER NOT NULL DEFAULT 0,"
                    + "name VARCHAR(13) NOT NULL DEFAULT '', level INTEGER NOT NULL DEFAULT 1, exp INTEGER NOT NULL DEFAULT 0,"
                    + "gachaexp INTEGER NOT NULL DEFAULT 0, str INTEGER NOT NULL DEFAULT 12, dex INTEGER NOT NULL DEFAULT 5,"
                    + "luk INTEGER NOT NULL DEFAULT 4, \"int\" INTEGER NOT NULL DEFAULT 4, hp INTEGER NOT NULL DEFAULT 50,"
                    + "mp INTEGER NOT NULL DEFAULT 5, maxhp INTEGER NOT NULL DEFAULT 50, maxmp INTEGER NOT NULL DEFAULT 5,"
                    + "meso INTEGER NOT NULL DEFAULT 0, hpMpUsed INTEGER NOT NULL DEFAULT 0, job INTEGER NOT NULL DEFAULT 0,"
                    + "skincolor INTEGER NOT NULL DEFAULT 0, gender INTEGER NOT NULL DEFAULT 0, fame INTEGER NOT NULL DEFAULT 0,"
                    + "fquest INTEGER NOT NULL DEFAULT 0, hair INTEGER NOT NULL DEFAULT 0, face INTEGER NOT NULL DEFAULT 0,"
                    + "ap INTEGER NOT NULL DEFAULT 0, sp VARCHAR(128) NOT NULL DEFAULT '0,0,0,0,0,0,0,0,0,0',"
                    + "map INTEGER NOT NULL DEFAULT 0, spawnpoint INTEGER NOT NULL DEFAULT 0, gm INTEGER NOT NULL DEFAULT 0,"
                    + "party INTEGER NOT NULL DEFAULT 0, buddyCapacity INTEGER NOT NULL DEFAULT 25,"
                    + "createdate TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, rank INTEGER NOT NULL DEFAULT 1,"
                    + "rankMove INTEGER NOT NULL DEFAULT 0, jobRank INTEGER NOT NULL DEFAULT 1,"
                    + "jobRankMove INTEGER NOT NULL DEFAULT 0, guildid INTEGER NOT NULL DEFAULT 0,"
                    + "guildrank INTEGER NOT NULL DEFAULT 5, messengerid INTEGER NOT NULL DEFAULT 0,"
                    + "messengerposition INTEGER NOT NULL DEFAULT 4, mountlevel INTEGER NOT NULL DEFAULT 1,"
                    + "mountexp INTEGER NOT NULL DEFAULT 0, mounttiredness INTEGER NOT NULL DEFAULT 0,"
                    + "omokwins INTEGER NOT NULL DEFAULT 0, omoklosses INTEGER NOT NULL DEFAULT 0,"
                    + "omokties INTEGER NOT NULL DEFAULT 0, matchcardwins INTEGER NOT NULL DEFAULT 0,"
                    + "matchcardlosses INTEGER NOT NULL DEFAULT 0, matchcardties INTEGER NOT NULL DEFAULT 0,"
                    + "MerchantMesos INTEGER NOT NULL DEFAULT 0, HasMerchant INTEGER NOT NULL DEFAULT 0,"
                    + "equipslots INTEGER NOT NULL DEFAULT 24, useslots INTEGER NOT NULL DEFAULT 24,"
                    + "setupslots INTEGER NOT NULL DEFAULT 24, etcslots INTEGER NOT NULL DEFAULT 24,"
                    + "familyId INTEGER NOT NULL DEFAULT -1, monsterbookcover INTEGER NOT NULL DEFAULT 0,"
                    + "allianceRank INTEGER NOT NULL DEFAULT 5, vanquisherStage INTEGER NOT NULL DEFAULT 0,"
                    + "ariantPoints INTEGER NOT NULL DEFAULT 0, dojoPoints INTEGER NOT NULL DEFAULT 0,"
                    + "lastDojoStage INTEGER NOT NULL DEFAULT 0, finishedDojoTutorial INTEGER NOT NULL DEFAULT 0,"
                    + "vanquisherKills INTEGER NOT NULL DEFAULT 0, summonValue INTEGER NOT NULL DEFAULT 0,"
                    + "partnerId INTEGER NOT NULL DEFAULT 0, marriageItemId INTEGER NOT NULL DEFAULT 0,"
                    + "reborns INTEGER NOT NULL DEFAULT 0, PQPoints INTEGER NOT NULL DEFAULT 0,"
                    + "dataString VARCHAR(64) NOT NULL DEFAULT '', lastLogoutTime TEXT NOT NULL DEFAULT '2015-01-01 05:00:00',"
                    + "lastExpGainTime TEXT NOT NULL DEFAULT '2015-01-01 05:00:00', partySearch INTEGER NOT NULL DEFAULT 1,"
                    + "jailexpire INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE queststatus ("
                    + "queststatusid INTEGER PRIMARY KEY, characterid INTEGER NOT NULL DEFAULT 0,"
                    + "quest INTEGER NOT NULL DEFAULT 0, status INTEGER NOT NULL DEFAULT 0,"
                    + "time INTEGER NOT NULL DEFAULT 0, expires BIGINT NOT NULL DEFAULT 0,"
                    + "forfeited INTEGER NOT NULL DEFAULT 0, completed INTEGER NOT NULL DEFAULT 0,"
                    + "info INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE questprogress ("
                    + "id INTEGER PRIMARY KEY, characterid INTEGER NOT NULL DEFAULT 0,"
                    + "queststatusid INTEGER NOT NULL DEFAULT 0, progressid INTEGER NOT NULL DEFAULT 0,"
                    + "progress VARCHAR(15) NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE inventoryitems ("
                    + "inventoryitemid INTEGER PRIMARY KEY, type INTEGER NOT NULL DEFAULT 0,"
                    + "characterid INTEGER, accountid INTEGER, itemid INTEGER NOT NULL DEFAULT 0,"
                    + "inventorytype INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0,"
                    + "quantity INTEGER NOT NULL DEFAULT 0, owner TEXT NOT NULL DEFAULT '',"
                    + "petid INTEGER NOT NULL DEFAULT -1, flag INTEGER NOT NULL DEFAULT 0,"
                    + "expiration INTEGER NOT NULL DEFAULT -1, giftFrom VARCHAR(26) NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE buddies ("
                    + "id INTEGER PRIMARY KEY, characterid INTEGER, buddyid INTEGER, pending INTEGER, "
                    + "\"group\" INTEGER)");
        }
    }

    private static void seedNewmapleData(DataSource source) throws Exception {
        try (Connection c = source.getConnection();
             Statement st = c.createStatement()) {
            // 两个账号：admin（老 SHA512 格式）+ newbie（BCrypt 格式）
            // 源表带 newmaple 一致 DEFAULT，其余列落默认值
            st.execute("INSERT INTO accounts (id, name, password) VALUES (1, 'admin', 'sha512$legacy')");
            st.execute("INSERT INTO accounts (id, name, password) VALUES (2, 'newbie', '$2a$10$kept')");
            st.execute("INSERT INTO characters (id, accountid, world, name, level, meso, map, job) "
                    + "VALUES (1, 1, 0, 'Hero', 10, 5000, 100000000, 100)");
            st.execute("INSERT INTO characters (id, accountid, world, name, level) "
                    + "VALUES (2, 2, 0, 'Mage', 20)");
            st.execute("INSERT INTO queststatus (queststatusid, characterid, quest, status, time,"
                    + " expires, forfeited, completed, info) "
                    + "VALUES (1, 1, 1000, 1, 0, 123456789, 0, 1, 1)");
            st.execute("INSERT INTO queststatus (queststatusid, characterid, quest, status, time) "
                    + "VALUES (2, 1, 1001, 2, 0)");
            st.execute("INSERT INTO questprogress (id, characterid, queststatusid, progressid, progress) "
                    + "VALUES (1, 1, 1, 10, '5'), (2, 1, 1, 20, '3')");
            st.execute("INSERT INTO inventoryitems (inventoryitemid, type, characterid, accountid, itemid,"
                    + " inventorytype, position, quantity, owner, petid, flag, expiration, giftFrom) "
                    + "VALUES (1, 1, NULL, NULL, 4000000, 1, 0, 1, '', -1, 0, -1, '')");
            st.execute("INSERT INTO inventoryitems (inventoryitemid, type, characterid, accountid, itemid,"
                    + " inventorytype, position, quantity, owner, petid, flag, expiration, giftFrom) "
                    + "VALUES (2, 2, 2, 2, 2000000, 2, 0, 5, 'owner', 0, 0, 0, 'gm')");
            st.execute("INSERT INTO buddies (id, characterid, buddyid, pending, \"group\") "
                    + "VALUES (1, 1, 2, 0, 0)");
        }
    }
}
