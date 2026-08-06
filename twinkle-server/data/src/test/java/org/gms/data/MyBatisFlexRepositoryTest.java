package org.gms.data;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 data 层集成验证：V2 迁移建表 + MyBatis-Flex 账号/角色增查（红线 2/3 结构兼容）。
 *
 * <p>用文件版 SQLite（:memory: 的 {@code ::} 会被属性解析截断，文件版无此问题）。
 * MyBatis-Flex 用 {@code new MybatisFlexBootstrap()} 独立实例（非静态单例），
 * 每个测试类独立装配，Mappers 静态注册表按环境 ID 覆盖，Surefire 串行下安全。
 */
class MyBatisFlexRepositoryTest {

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

        // 角色插入 + 按账号/世界查询（选角列表）
        Character hero = new Character();
        hero.setAccountid(acc.getId());
        hero.setWorld(0);
        hero.setName("Hero");
        hero.setLevel(10);
        hero.setJob(100);
        characterMapper.insertSelective(hero);

        Character otherWorld = new Character();
        otherWorld.setAccountid(acc.getId());
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
    }

    /**
     * 注入防御 demo（安全门槛 M0 第 1 条 / 红线 18）：恶意载荷经 Repository 参数化写入，
     * 断言原样存库、不执行注入（accounts 表仍在，后续查询可用）。走 MyBatis-Flex {@code #{}}
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
        String payload = "evil'; DROP TABLE accounts; --";
        Account acc = new Account();
        acc.setName(payload);
        acc.setPassword("pwd");
        acc.setBanned(0);
        accountMapper.insertSelective(acc);

        // 断言 1：载荷被原样存储（findByName 参数化命中）
        assertThat(accountRepo.findByName(payload)).isPresent();
        // 断言 2：accounts 表未被 DROP——再插一条并查询成功
        Account second = new Account();
        second.setName("tester");
        second.setPassword("pwd");
        second.setBanned(0);
        accountMapper.insertSelective(second);
        assertThat(accountRepo.findByName("tester")).isPresent();
    }
}
