package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.Account;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.tick.GameTickLoop;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 进程兜底端到端（架构 5.4 路径 B：DRAINING → 增量 FLUSH → 重启 → 恢复，只刷脏数据红线 17）。
 *
 * <p>手动装配：临时 SQLite + 迁移 + 存档队列 + RestartService（restart runnable 用 mock 代替
 * System.exit）。验证：
 * <ul>
 *   <li>状态推进 DRAINING → FLUSH_DIRTY → RESTARTING → RESTORED。</li>
 *   <li>DB 中 meso 已更新（脏数据落库）。</li>
 *   <li>在线角色脏位已清。</li>
 *   <li>fromData(DB) 拿到与内存一致的值（"原地复活"数据面）。</li>
 * </ul>
 */
class L4RestartE2ETest {

    @Test
    void restartFlushesDirtyAndRecoversFromDb() throws Exception {
        // ---- 数据层：临时 SQLite + 迁移 + 账号/角色 ----
        String dbPath = Files.createTempDirectory("twinkle-l4-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("l4-e2e-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        acc.setBanned(0);
        acc.setGender(0);
        flex.getMapper(AccountMapper.class).insertSelective(acc);

        org.gms.data.entity.Character heroDb = new org.gms.data.entity.Character();
        heroDb.setAccountid(acc.getId());
        heroDb.setWorld(0);
        heroDb.setName("Hero");
        heroDb.setLevel(10);
        heroDb.setJob(0);
        heroDb.setSkincolor(0);
        heroDb.setGender(0);
        heroDb.setFace(20000);
        heroDb.setHair(30000);
        heroDb.setStr((short) 40);
        heroDb.setDex((short) 5);
        heroDb.setLuk((short) 4);
        heroDb.setIntStat((short) 4);
        heroDb.setHp((short) 500);
        heroDb.setMp((short) 5);
        heroDb.setMaxhp((short) 500);
        heroDb.setMaxmp((short) 5);
        heroDb.setMap(100000000);
        heroDb.setSpawnpoint(0);
        heroDb.setBuddyCapacity(25);
        heroDb.setEquipslots(24);
        heroDb.setUseslots(24);
        heroDb.setSetupslots(24);
        heroDb.setEtcslots(24);
        characterMapper.insertSelective(heroDb);
        long heroId = heroDb.getId();

        // ---- 频道装配：loader / 在线表 / 存档队列 / 重启编排 ----
        DefaultVersionGate versionGate = new DefaultVersionGate();
        CharacterLoader loader = new CharacterLoader(versionGate);
        PlayerStorage players = new PlayerStorage();
        FlexCharacterRepository repo = new FlexCharacterRepository(characterMapper);
        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repo, loader, players);
        GameTickLoop tickLoop = new GameTickLoop(5);
        EntityReloadCoordinator coordinator = new EntityReloadCoordinator();
        EntityReloadService reloadService = new EntityReloadService(coordinator, versionGate);
        RestartCoordinator restartCoordinator = new RestartCoordinator();
        RestartService restartService = new RestartService(restartCoordinator, tickLoop, reloadService, saveQueue);

        try {
            // ---- 玩家进图（内存态） + 改 meso（dirty） ----
            var chr = loader.fromData(repo.findById(heroId).get());
            chr.setMap(999999999); // 换图（持久化字段，标脏）
            chr.setMeso(12345);
            players.add(chr);
            assertThat(chr.isDirty()).isTrue();

            // ---- 重启编排（mock restart，不真正退出） ----
            AtomicReference<RestartCoordinator.Phase> lastPhase = new AtomicReference<>();
            restartCoordinator.onPhaseChange(lastPhase::set);
            AtomicReference<Boolean> restarted = new AtomicReference<>(false);
            restartService.restart(() -> restarted.set(true));

            // 状态推进
            assertThat(restarted.get()).isTrue();
            assertThat(lastPhase.get()).isEqualTo(RestartCoordinator.Phase.RESTORED);
            // 脏位已清（DRAINING saveQueue.drain 落库 + FLUSH_DIRTY）
            assertThat(chr.isDirty()).isFalse();

            // ---- DB 真值 = 重启前内存值（增量 FLUSH 已落库） ----
            var dbAfter = repo.findById(heroId).get();
            assertThat(dbAfter.getMeso()).isEqualTo(12345);
            assertThat(dbAfter.getMap()).isEqualTo(999999999);

            // ---- 上下文恢复：fromData(DB) 拿到一致值（"原地复活"数据面） ----
            var recovered = loader.fromData(dbAfter);
            assertThat(recovered.getMeso()).isEqualTo(12345);
            assertThat(recovered.getMap()).isEqualTo(999999999);
            assertThat(recovered.getLevel()).isEqualTo(10);
            assertThat(recovered.isDirty()).isFalse(); // 加载态即已落盘
        } finally {
            saveQueue.close();
            tickLoop.stop();
        }
    }
}
