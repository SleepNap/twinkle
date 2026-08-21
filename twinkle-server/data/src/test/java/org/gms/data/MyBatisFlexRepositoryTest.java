package org.gms.data;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.mapper.SkillMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.data.repo.FlexCharacterSnapshotRepository;
import org.gms.data.repo.FlexInventoryItemRepository;
import org.gms.data.repo.FlexQuestRepository;
import org.gms.data.repo.QuestProgressSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 data 层集成验证：V2 迁移建表 + MyBatis-Flex 账号/角色增查（红线 2/3 结构兼容）。
 *
 * <p>用文件版 SQLite（:memory: 的 {@code ::} 会被属性解析截断，文件版无此问题）。
 * MyBatis-Flex 用 {@code new MybatisFlexBootstrap()} 独立实例（非静态单例），
 * 每个测试类独立装配，Mappers 静态注册表按环境 ID 覆盖，Surefire 串行下安全。
 */
class MyBatisFlexRepositoryTest {

    @Test
    void questReplaceAllRebuildsForeignKeysAndDeletesOldProgress() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-quest-replace").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(ds);
        bootstrap.setEnvironmentId("quest-" + dbPath);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.start();

        FlexQuestRepository repository = new FlexQuestRepository(
                bootstrap.getMapper(QuestStatusMapper.class),
                bootstrap.getMapper(QuestProgressMapper.class));
        QuestStatusEntity status = new QuestStatusEntity();
        status.setCharacterId(9);
        status.setQuest(1000);
        status.setStatus(1);
        status.setTime(-1);
        repository.replaceAll(9L, java.util.List.of(status),
                java.util.List.of(new QuestProgressSnapshot(1000, 100_001, "003")));

        QuestStatusEntity savedStatus = repository.findStatusesByCharacterId(9L).getFirst();
        assertThat(savedStatus.getQuestStatusId()).isPositive();
        assertThat(repository.findProgressByCharacterId(9L)).singleElement().satisfies(saved -> {
            assertThat(saved.getQuestStatusId()).isEqualTo(savedStatus.getQuestStatusId().intValue());
            assertThat(saved.getProgressId()).isEqualTo(100_001);
            assertThat(saved.getProgress()).isEqualTo("003");
        });

        repository.replaceAll(9L, java.util.List.of(), java.util.List.of());
        assertThat(repository.findStatusesByCharacterId(9L)).isEmpty();
        assertThat(repository.findProgressByCharacterId(9L)).isEmpty();
    }

    @Test
    void characterAndInventorySnapshotRollBackTogether() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-snapshot-transaction").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(ds);
        bootstrap.setEnvironmentId("snapshot-" + dbPath);
        bootstrap.addMapper(CharacterMapper.class);
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.addMapper(SkillMapper.class);
        bootstrap.start();

        CharacterMapper characterMapper = bootstrap.getMapper(CharacterMapper.class);
        InventoryItemMapper inventoryMapper = bootstrap.getMapper(InventoryItemMapper.class);
        FlexCharacterRepository characterRepository = new FlexCharacterRepository(characterMapper);
        FlexInventoryItemRepository inventoryRepository = new FlexInventoryItemRepository(inventoryMapper);
        FlexCharacterSnapshotRepository snapshotRepository = new FlexCharacterSnapshotRepository(
                characterMapper, inventoryMapper,
                bootstrap.getMapper(QuestStatusMapper.class),
                bootstrap.getMapper(QuestProgressMapper.class),
                bootstrap.getMapper(SkillMapper.class));

        Character hero = new Character();
        hero.setAccountId(1L);
        hero.setWorld(0);
        hero.setName("SnapshotHero");
        hero.setLevel(10);
        characterRepository.insert(hero);

        InventoryItemEntity original = new InventoryItemEntity();
        original.setCharacterId(hero.getId().intValue());
        original.setAccountId(1);
        original.setItemId(2_000_000);
        original.setInventoryType(2);
        original.setPosition(1);
        original.setQuantity(5);
        original.setOwner("");
        original.setGiftFrom("");
        inventoryRepository.insert(original);

        Character changed = characterRepository.findById(hero.getId()).orElseThrow();
        changed.setLevel(99);
        InventoryItemEntity invalid = new InventoryItemEntity();
        invalid.setCharacterId(hero.getId().intValue());
        invalid.setAccountId(1);
        invalid.setItemId(2_000_001);
        invalid.setInventoryType(2);
        invalid.setPosition(1);
        invalid.setQuantity(1);
        invalid.setOwner(null); // owner NOT NULL：故意让背包写入失败，验证主表更新与删除均回滚。
        invalid.setGiftFrom("");

        assertThatThrownBy(() -> snapshotRepository.save(
                changed, java.util.List.of(invalid), java.util.List.of(),
                java.util.List.of(), java.util.List.of()))
                .isInstanceOf(RuntimeException.class);

        assertThat(characterRepository.findById(hero.getId()).orElseThrow().getLevel()).isEqualTo(10);
        assertThat(inventoryRepository.findByCharacterId(hero.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getItemId()).isEqualTo(2_000_000);
                    assertThat(saved.getQuantity()).isEqualTo(5);
                });
    }

    @Test
    void inventoryDetailsRoundTripViaFlex() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-inventory-test").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(ds);
        bootstrap.setEnvironmentId("inventory-" + dbPath);
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.start();

        FlexInventoryItemRepository repository = new FlexInventoryItemRepository(
                bootstrap.getMapper(InventoryItemMapper.class));
        InventoryItemEntity equip = new InventoryItemEntity();
        equip.setType(1);
        equip.setCharacterId(9);
        equip.setAccountId(3);
        equip.setItemId(1040002);
        equip.setInventoryType(1);
        equip.setPosition(-5);
        equip.setQuantity(1);
        equip.setOwner("Hero");
        equip.setGiftFrom("");
        equip.setCashId(7001);
        equip.setUpgradeSlots(5);
        equip.setStr(12);
        equip.setWatk(3);
        equip.setItemLevel(4);
        equip.setItemExp(4567L);
        InventoryItemEntity pet = new InventoryItemEntity();
        pet.setType(3);
        pet.setCharacterId(9);
        pet.setAccountId(3);
        pet.setItemId(5_000_000);
        pet.setInventoryType(5);
        pet.setPosition(2);
        pet.setQuantity(1);
        pet.setOwner("");
        pet.setGiftFrom("");
        pet.setPetId(9001);
        pet.setPetName("小黑");
        pet.setPetLevel(12);
        pet.setPetCloseness(3456);
        pet.setPetFullness(87);
        pet.setPetAttribute(3);
        pet.setPetSkill(4);
        pet.setPetRemainLife(17_500);
        pet.setItemAttribute(5);

        repository.replaceAll(9L, java.util.List.of(equip, pet));

        assertThat(repository.findByCharacterId(9L)).hasSize(2);
        assertThat(repository.findByCharacterId(9L)).filteredOn(saved -> saved.getType() == 1)
                .singleElement().satisfies(saved -> {
            assertThat(saved.getCashId()).isEqualTo(7001);
            assertThat(saved.getUpgradeSlots()).isEqualTo(5);
            assertThat(saved.getStr()).isEqualTo(12);
            assertThat(saved.getWatk()).isEqualTo(3);
            assertThat(saved.getItemLevel()).isEqualTo(4);
            assertThat(saved.getItemExp()).isEqualTo(4567L);
        });
        assertThat(repository.findByCharacterId(9L)).filteredOn(saved -> saved.getType() == 3)
                .singleElement().satisfies(saved -> {
            assertThat(saved.getPetId()).isEqualTo(9001);
            assertThat(saved.getPetName()).isEqualTo("小黑");
            assertThat(saved.getPetLevel()).isEqualTo(12);
            assertThat(saved.getPetCloseness()).isEqualTo(3456);
            assertThat(saved.getPetFullness()).isEqualTo(87);
            assertThat(saved.getPetAttribute()).isEqualTo(3);
            assertThat(saved.getPetSkill()).isEqualTo(4);
            assertThat(saved.getPetRemainLife()).isEqualTo(17_500);
            assertThat(saved.getItemAttribute()).isEqualTo(5);
        });
    }

    @Test
    void accountAndCharacterCrudViaFlex() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-flex-test").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(ds);
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.addMapper(CharacterMapper.class);
        bootstrap.start();

        AccountMapper accountMapper = bootstrap.getMapper(AccountMapper.class);
        CharacterMapper characterMapper = bootstrap.getMapper(CharacterMapper.class);

        // 账号插入 + 按名查询（insertSelective：只插已设置字段，其余列用 DB DEFAULT）
        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword("stored-hash");
        acc.setBanned(0);
        accountMapper.insertSelective(acc);

        FlexAccountRepository accountRepo = new FlexAccountRepository(accountMapper);
        assertThat(accountRepo.findByName("tester")).isPresent();
        assertThat(accountRepo.findByName("tester").get().getPassword()).isEqualTo("stored-hash");
        assertThat(accountRepo.findByName("nobody")).isEmpty();

        Account banned = new Account();
        banned.setName("tester-banned");
        banned.setPassword("stored-hash");
        banned.setBanned(1);
        accountMapper.insertSelective(banned);
        assertThat(accountRepo.findPage("tester", null, 0, 1)).satisfies(page -> {
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.records()).hasSize(1);
        });
        assertThat(accountRepo.findPage("tester", true, 0, 20).records())
                .extracting(Account::getName)
                .containsExactly("tester-banned");

        // 角色插入 + 按账号/世界查询（选角列表）
        Character hero = new Character();
        hero.setAccountId(acc.getId());
        hero.setWorld(0);
        hero.setName("Hero");
        hero.setLevel(10);
        hero.setJob(100);
        characterMapper.insertSelective(hero);

        Character otherWorld = new Character();
        otherWorld.setAccountId(acc.getId());
        otherWorld.setWorld(1);
        otherWorld.setName("Other");
        otherWorld.setLevel(20);
        characterMapper.insertSelective(otherWorld);

        FlexCharacterRepository charRepo = new FlexCharacterRepository(characterMapper);
        assertThat(charRepo.findByAccount(acc.getId().intValue(), 0))
                .extracting(Character::getName)
                .containsExactly("Hero");
        assertThat(charRepo.findByAccount(acc.getId().intValue(), 1))
                .extracting(Character::getName)
                .containsExactly("Other");
        assertThat(charRepo.findByAccount(acc.getId()))
                .extracting(Character::getName)
                .containsExactly("Hero", "Other");
    }

    /**
     * 注入防御 demo（安全门槛 M0 第 1 条 / 红线 18）：恶意载荷经 Repository 参数化写入，
     * 断言原样存库、不执行注入（account_records 表仍在，后续查询可用）。走 MyBatis-Flex {@code #{}}
     * 参数化即天然免疫。
     */
    @Test
    void sqlInjectionPayloadStoredLiterally() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-sqli-test").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(ds);
        // 同一类内多个测试共存：Mappers/FlexGlobalConfig 是静态注册表，必须 setEnvironmentId(唯一值)，
        // 否则 getMapper 拿到上一测试的旧 mapper（指向别的 db）——见 m1-progress 记忆。
        bootstrap.setEnvironmentId("sqli-" + dbPath);
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.start();

        AccountMapper accountMapper = bootstrap.getMapper(AccountMapper.class);
        FlexAccountRepository accountRepo = new FlexAccountRepository(accountMapper);

        // 恶意载荷：若走字符串拼接即成注入（DROP 表），参数化则原样存库
        String payload = "evil'; DROP TABLE account_records; --";
        Account acc = new Account();
        acc.setName(payload);
        acc.setPassword("pwd");
        acc.setBanned(0);
        accountMapper.insertSelective(acc);

        // 断言 1：载荷被原样存储（findByName 参数化命中）
        assertThat(accountRepo.findByName(payload)).isPresent();
        // 断言 2：account_records 表未被 DROP——再插一条并查询成功
        Account second = new Account();
        second.setName("tester");
        second.setPassword("pwd");
        second.setBanned(0);
        accountMapper.insertSelective(second);
        assertThat(accountRepo.findByName("tester")).isPresent();
    }
}
