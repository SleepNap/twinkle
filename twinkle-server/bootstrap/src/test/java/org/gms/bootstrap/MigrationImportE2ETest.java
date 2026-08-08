package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.channel.CharacterLoader;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.data.tools.NewMapleImporter;
import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-2 验收：单库迁移后"老存档可读、行为对齐参考项目"（架构 M5-2 第 3 项数据验证）。
 *
 * <p>old-format newmaple 源库（模拟）→ NewMapleImporter 导入 → 目标库 → 经
 * {@link CharacterLoader.fromData} 加载导入角色，断言领域层字段与源值一致（证明游戏内可读、
 * 74 列存档格式兼容）。这是"迁移后老存档可读"的端到端证据。
 */
class MigrationImportE2ETest {

    @Test
    void importedCharacterLoadsIntoGameDomain() throws Exception {
        // ---- 源库（newmaple old-format）----
        String sourcePath = Files.createTempDirectory("twinkle-imp-src").resolve("old.db").toString();
        String targetPath = Files.createTempDirectory("twinkle-imp-dst").resolve("new.db").toString();
        DataSource source = new SimpleDriverDataSource("jdbc:sqlite:" + sourcePath, "", "");
        DataSource target = new SimpleDriverDataSource("jdbc:sqlite:" + targetPath, "", "");

        createNewmapleSource(source);
        seed(source);

        // ---- 目标库迁移 + 导入 ----
        MigrationRunner.applyMigrations(target, "sqlite");
        NewMapleImporter.copy(source, target, "sqlite",
                NewMapleImporter.CopyOptions.defaults(null));

        // ---- MyBatis-Flex 读目标库 ----
        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("imp-e2e-" + targetPath);
        flex.setDataSource(target);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        FlexCharacterRepository repo = new FlexCharacterRepository(flex.getMapper(CharacterMapper.class));

        // ---- 老存档可读：CharacterLoader 加载导入角色，领域层字段与源一致 ----
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        Character hero = loader.fromData(repo.findById(1L).orElseThrow());
        assertThat(hero.getName()).isEqualTo("Hero");
        assertThat(hero.getLevel()).isEqualTo(10);
        assertThat(hero.getMeso()).isEqualTo(5000);
        assertThat(hero.getMap()).isEqualTo(100000000);
        assertThat(hero.getJob()).isEqualTo(100);
        assertThat(hero.getStr()).isEqualTo((short) 40);
        assertThat(hero.getDex()).isEqualTo((short) 5);
        assertThat(hero.getLuk()).isEqualTo((short) 4);
        assertThat(hero.getIntStat()).isEqualTo((short) 4);
        assertThat(hero.getHp()).isEqualTo((short) 500);
        assertThat(hero.getMaxHp()).isEqualTo((short) 500);
        assertThat(hero.getMap()).isEqualTo(100000000);
        assertThat(hero.isDirty()).isFalse(); // 加载态即已落盘（L4 增量 FLUSH 依据）

        // 第二角色（只设了部分列的 Mage）——未设列走源 DEFAULT，加载不丢
        Character mage = loader.fromData(repo.findById(2L).orElseThrow());
        assertThat(mage.getName()).isEqualTo("Mage");
        assertThat(mage.getLevel()).isEqualTo(20);
        assertThat(mage.getJob()).isEqualTo(0);
    }

    /** old-format 源库（与 NewMapleImporterTest 同构，但只建本测试需要的表）。 */
    private static void createNewmapleSource(DataSource source) throws Exception {
        try (Connection c = source.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts ("
                    + "id INTEGER PRIMARY KEY, name VARCHAR(13) NOT NULL DEFAULT '', password VARCHAR(128) NOT NULL DEFAULT '',"
                    + "loggedin INTEGER NOT NULL DEFAULT 0, banned INTEGER NOT NULL DEFAULT 0, characterslots INTEGER NOT NULL DEFAULT 3,"
                    + "gender INTEGER NOT NULL DEFAULT 10, birthday TEXT NOT NULL DEFAULT '2005-05-11',"
                    + "tempban TEXT NOT NULL DEFAULT '2005-05-11 00:00:00', language INTEGER NOT NULL DEFAULT 3,"
                    + "pin VARCHAR(10) NOT NULL DEFAULT '', pic VARCHAR(26) NOT NULL DEFAULT '',"
                    + "createdat TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, lastlogin TEXT, banreason TEXT, macs TEXT,"
                    + "nxCredit INTEGER, maplePoint INTEGER, nxPrepaid INTEGER, greason INTEGER NOT NULL DEFAULT 0,"
                    + "tos INTEGER NOT NULL DEFAULT 0, sitelogged TEXT, webadmin INTEGER, nick TEXT, mute INTEGER,"
                    + "email TEXT, ip TEXT, rewardpoints INTEGER NOT NULL DEFAULT 0, votepoints INTEGER NOT NULL DEFAULT 0,"
                    + "hwid TEXT NOT NULL DEFAULT '')");
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
                    + "dataString VARCHAR(64) NOT NULL DEFAULT '',"
                    + "lastLogoutTime TEXT NOT NULL DEFAULT '2015-01-01 05:00:00',"
                    + "lastExpGainTime TEXT NOT NULL DEFAULT '2015-01-01 05:00:00',"
                    + "partySearch INTEGER NOT NULL DEFAULT 1, jailexpire INTEGER NOT NULL DEFAULT 0)");
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

    private static void seed(DataSource source) throws Exception {
        try (Connection c = source.getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO accounts (id, name, password) VALUES (1, 'admin', 'legacy-hash')");
            st.execute("INSERT INTO accounts (id, name, password) VALUES (2, 'mage', 'legacy-hash')");
            st.execute("INSERT INTO characters (id, accountid, world, name, level, meso, map, job,"
                    + " str, dex, luk, \"int\", hp, maxhp, maxmp) "
                    + "VALUES (1, 1, 0, 'Hero', 10, 5000, 100000000, 100, 40, 5, 4, 4, 500, 500, 5)");
            st.execute("INSERT INTO characters (id, accountid, world, name, level) VALUES (2, 2, 0, 'Mage', 20)");
            st.execute("INSERT INTO queststatus (queststatusid, characterid, quest, status, time,"
                    + " expires, forfeited, completed, info) VALUES (1, 1, 1000, 1, 0, 123456789, 0, 1, 1)");
            st.execute("INSERT INTO questprogress (id, characterid, queststatusid, progressid, progress) "
                    + "VALUES (1, 1, 1, 10, '5'), (2, 1, 1, 20, '3')");
            st.execute("INSERT INTO inventoryitems (inventoryitemid, type, characterid, accountid, itemid,"
                    + " inventorytype, position, quantity, owner, petid, flag, expiration, giftFrom) "
                    + "VALUES (1, 1, NULL, NULL, 4000000, 1, 0, 1, '', -1, 0, -1, '')");
            st.execute("INSERT INTO buddies (id, characterid, buddyid, pending, \"group\") VALUES (1, 1, 2, 0, 0)");
        }
    }
}
