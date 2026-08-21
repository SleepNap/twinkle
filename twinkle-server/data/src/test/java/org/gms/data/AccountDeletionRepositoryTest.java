package org.gms.data;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.mapper.AccountAdminRoleMapper;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.AdminSessionMapper;
import org.gms.data.mapper.ApiKeyMapper;
import org.gms.data.mapper.BuddyListMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.PointAccountMapper;
import org.gms.data.mapper.PointTransactionMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.mapper.SkillMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountDeletionRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号删除必须覆盖当前全部账号级、角色级业务关联表，同时保留无关账号数据。 */
class AccountDeletionRepositoryTest {

    @Test
    void deletesAccountCharactersAndEveryBusinessRelationInOneOperation() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-account-delete").resolve("test.db").toString();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(dataSource, "sqlite");

        MybatisFlexBootstrap bootstrap = bootstrap(dataSource, dbPath);
        seed(dataSource);
        FlexAccountDeletionRepository repository = new FlexAccountDeletionRepository(
                bootstrap.getMapper(AccountMapper.class),
                bootstrap.getMapper(CharacterMapper.class),
                bootstrap.getMapper(InventoryItemMapper.class),
                bootstrap.getMapper(QuestStatusMapper.class),
                bootstrap.getMapper(QuestProgressMapper.class),
                bootstrap.getMapper(SkillMapper.class),
                bootstrap.getMapper(BuddyListMapper.class),
                bootstrap.getMapper(PointAccountMapper.class),
                bootstrap.getMapper(PointTransactionMapper.class),
                bootstrap.getMapper(AccountAdminRoleMapper.class),
                bootstrap.getMapper(AdminSessionMapper.class),
                bootstrap.getMapper(ApiKeyMapper.class));

        var result = repository.deleteByAccountId(7L);

        assertThat(result.characters()).isEqualTo(2);
        assertThat(result.relatedRows()).isEqualTo(12);
        assertThat(count(dataSource, "SELECT COUNT(*) FROM account_records WHERE id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM character_records WHERE account_id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM inventory_items WHERE account_id = 7 OR character_id IN (71, 72)")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM quest_status WHERE character_id IN (71, 72)")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM quest_progress WHERE character_id IN (71, 72)")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM skills WHERE characterid IN (71, 72)")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM buddy_list WHERE owner_id IN (71, 72) OR buddy_id IN (71, 72)")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM point_account WHERE account_id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM point_transaction WHERE account_id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM account_admin_role WHERE account_id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM admin_session WHERE account_id = 7")).isZero();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM api_key_records WHERE owner_account_id = 7")).isZero();

        assertThat(count(dataSource, "SELECT COUNT(*) FROM account_records WHERE id = 8")).isOne();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM character_records WHERE id = 81")).isOne();
        assertThat(count(dataSource, "SELECT COUNT(*) FROM buddy_list WHERE owner_id = 81")).isOne();
    }

    private static MybatisFlexBootstrap bootstrap(DataSource dataSource, String environmentId) {
        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(dataSource);
        bootstrap.setEnvironmentId("account-delete-" + environmentId);
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.addMapper(CharacterMapper.class);
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.addMapper(SkillMapper.class);
        bootstrap.addMapper(BuddyListMapper.class);
        bootstrap.addMapper(PointAccountMapper.class);
        bootstrap.addMapper(PointTransactionMapper.class);
        bootstrap.addMapper(AccountAdminRoleMapper.class);
        bootstrap.addMapper(AdminSessionMapper.class);
        bootstrap.addMapper(ApiKeyMapper.class);
        bootstrap.start();
        return bootstrap;
    }

    private static void seed(DataSource dataSource) throws Exception {
        execute(dataSource, "INSERT INTO account_records(id, name, password) VALUES (7, 'delete-me', 'hash')");
        execute(dataSource, "INSERT INTO account_records(id, name, password) VALUES (8, 'keep-me', 'hash')");
        execute(dataSource, "INSERT INTO character_records(id, account_id, name) VALUES (71, 7, 'HeroOne')");
        execute(dataSource, "INSERT INTO character_records(id, account_id, name) VALUES (72, 7, 'HeroTwo')");
        execute(dataSource, "INSERT INTO character_records(id, account_id, name) VALUES (81, 8, 'KeepHero')");
        execute(dataSource, "INSERT INTO quest_status(quest_status_id, character_id, quest) VALUES (1, 71, 1000)");
        execute(dataSource, "INSERT INTO quest_progress(character_id, quest_status_id, progress_id) VALUES (71, 1, 100001)");
        execute(dataSource, "INSERT INTO inventory_items(character_id, account_id, owner) VALUES (71, 7, '')");
        execute(dataSource, "INSERT INTO inventory_items(character_id, account_id, owner) VALUES (0, 7, '')");
        execute(dataSource, "INSERT INTO skills(skillid, characterid) VALUES (1001004, 71)");
        execute(dataSource, "INSERT INTO buddy_list(owner_id, buddy_id) VALUES (71, 999)");
        execute(dataSource, "INSERT INTO buddy_list(owner_id, buddy_id) VALUES (999, 72)");
        execute(dataSource, "INSERT INTO buddy_list(owner_id, buddy_id) VALUES (81, 999)");
        execute(dataSource, "INSERT INTO point_account(account_id) VALUES (7)");
        execute(dataSource, "INSERT INTO point_transaction(account_id) VALUES (7)");
        execute(dataSource, "INSERT INTO account_admin_role(account_id, role_id) VALUES (7, 1)");
        execute(dataSource, "INSERT INTO admin_session(token_prefix, token_hash, account_id) VALUES ('prefix', 'hash', 7)");
        execute(dataSource, "INSERT INTO api_key_records(credential_id, key_prefix, secret_hash, subject_id, subject_display_name, created_by_subject_id, server_id, owner_account_id, scopes, permission_version) VALUES ('credential', 'key-prefix', 'hash', 'subject', 'subject', 'creator', 'server', 7, 'game:read', 'v1')");
    }

    private static void execute(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long count(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.getLong(1);
        }
    }
}
