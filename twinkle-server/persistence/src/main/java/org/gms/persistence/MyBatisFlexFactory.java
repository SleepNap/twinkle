package org.gms.persistence;

import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.MybatisFlexBootstrap;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.gms.persistence.config.FlexParamConfRepository;
import org.gms.persistence.config.ParamConfRepository;
import org.gms.persistence.mapper.GameAccountRecordMapper;
import org.gms.persistence.mapper.AccountAdminRoleMapper;
import org.gms.persistence.mapper.AdminOperationAuditMapper;
import org.gms.persistence.mapper.AdminRoleMapper;
import org.gms.persistence.mapper.AdminSessionMapper;
import org.gms.persistence.mapper.ApiKeyMapper;
import org.gms.persistence.mapper.ApiRequestAuditMapper;
import org.gms.persistence.mapper.BuddyListMapper;
import org.gms.persistence.mapper.BusOutboxMapper;
import org.gms.persistence.mapper.BusStreamMapper;
import org.gms.persistence.mapper.PlayerCharacterRecordMapper;
import org.gms.persistence.mapper.InventoryItemMapper;
import org.gms.persistence.mapper.ParamConfMapper;
import org.gms.persistence.mapper.QuestProgressMapper;
import org.gms.persistence.mapper.QuestStatusMapper;
import org.gms.persistence.mapper.SkillMapper;
import org.gms.persistence.mapper.ToolExecutionAuditMapper;
import org.gms.persistence.mapper.PointAccountMapper;
import org.gms.persistence.mapper.PointTransactionMapper;
import org.gms.persistence.mapper.SubscriptionPlanMapper;
import org.gms.persistence.repo.GameAccountRepository;
import org.gms.persistence.repo.AccountDeletionRepository;
import org.gms.persistence.repo.AccountAdminRoleRepository;
import org.gms.persistence.repo.AdminOperationAuditRepository;
import org.gms.persistence.repo.AdminRoleRepository;
import org.gms.persistence.repo.AdminSessionRepository;
import org.gms.persistence.repo.ApiKeyRepository;
import org.gms.persistence.repo.ApiRequestAuditRepository;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.persistence.repo.PlayerCharacterSnapshotRepository;
import org.gms.persistence.repo.FlexGameAccountRepository;
import org.gms.persistence.repo.FlexAccountDeletionRepository;
import org.gms.persistence.repo.FlexAccountAdminRoleRepository;
import org.gms.persistence.repo.FlexAdminOperationAuditRepository;
import org.gms.persistence.repo.FlexAdminRoleRepository;
import org.gms.persistence.repo.FlexAdminSessionRepository;
import org.gms.persistence.repo.FlexApiKeyRepository;
import org.gms.persistence.repo.FlexApiRequestAuditRepository;
import org.gms.persistence.repo.FlexBuddyListRepository;
import org.gms.persistence.repo.FlexBusOutboxRepository;
import org.gms.persistence.repo.FlexPlayerCharacterRepository;
import org.gms.persistence.repo.FlexPlayerCharacterSnapshotRepository;
import org.gms.persistence.repo.FlexInventoryItemRepository;
import org.gms.persistence.repo.FlexQuestRepository;
import org.gms.persistence.repo.InventoryItemRepository;
import org.gms.persistence.repo.QuestRepository;
import org.gms.persistence.repo.FlexSkillRepository;
import org.gms.persistence.repo.SkillRepository;
import org.gms.persistence.repo.ToolExecutionAuditRepository;
import org.gms.persistence.repo.FlexToolExecutionAuditRepository;
import org.gms.persistence.repo.PointAccountRepository;
import org.gms.persistence.repo.PointTransactionRepository;
import org.gms.persistence.repo.SubscriptionPlanRepository;
import org.gms.persistence.repo.FlexPointAccountRepository;
import org.gms.persistence.repo.FlexPointTransactionRepository;
import org.gms.persistence.repo.FlexSubscriptionPlanRepository;
import org.gms.event.OutboxRepository;

import javax.sql.DataSource;

/**
 * MyBatis-Flex 装配工厂（架构 6.2：M1 接入 SqlSessionFactory 替换 JDBC 仓库）。
 *
 * <p>非 Spring 场景用 {@link MybatisFlexBootstrap}：设置数据源、注册 mapper、start 构建
 * SqlSessionFactory。官方 javadoc 明确允许 {@code new MybatisFlexBootstrap()} 多实例
 * （管理多数据源），因此**不用静态单例**——每个 ApplicationContext 装配独立实例，
 * 避免测试/多 context 间共享冲突（MyBatis-Flex 的 Mappers 静态注册表按 environmentId
 * 覆盖，测试串行下安全）。
 *
 * <p>生命周期：{@code @Context} 强制启动装配（与 DataSourceFactory 一致），使 mapper /
 * repository 缺依赖在启动期暴露，而非运行期才炸。
 */
@Factory
public class MyBatisFlexFactory {



    @Bean
    @Singleton
    @Context
    public MybatisFlexBootstrap flexBootstrap(DataSource dataSource) {
        // 关 MyBatis-Flex 启动 banner（架构红线 6：日志统一，禁止直接写标准输出）。
        // 非 Spring 场景（MybatisFlexBootstrap 手写装配）yml 的 mybatis-flex.global-config
        // 键无人解析，必须在这里显式设置 FlexGlobalConfig。
        FlexGlobalConfig.getDefaultConfig().setPrintBanner(false);
        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(dataSource);
        bootstrap.addMapper(ParamConfMapper.class);
        bootstrap.addMapper(GameAccountRecordMapper.class);
        bootstrap.addMapper(PlayerCharacterRecordMapper.class);
        // M3-5 存档表（进图回填/下线落库；M3-1 HTTP 查存档也依赖，统一在此注册）
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.addMapper(SkillMapper.class);
        bootstrap.addMapper(BusOutboxMapper.class);
        bootstrap.addMapper(BusStreamMapper.class);
        bootstrap.addMapper(BuddyListMapper.class);
        bootstrap.addMapper(ApiKeyMapper.class);
        bootstrap.addMapper(ApiRequestAuditMapper.class);
        bootstrap.addMapper(ToolExecutionAuditMapper.class);
        bootstrap.addMapper(PointAccountMapper.class);
        bootstrap.addMapper(SubscriptionPlanMapper.class);
        bootstrap.addMapper(PointTransactionMapper.class);
        bootstrap.addMapper(AdminRoleMapper.class);
        bootstrap.addMapper(AccountAdminRoleMapper.class);
        bootstrap.addMapper(AdminSessionMapper.class);
        bootstrap.addMapper(AdminOperationAuditMapper.class);
        bootstrap.start();
        return bootstrap;
    }

    @Bean
    @Singleton
    public GameAccountRecordMapper accountMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(GameAccountRecordMapper.class);
    }

    @Bean
    @Singleton
    public PlayerCharacterRecordMapper characterMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(PlayerCharacterRecordMapper.class);
    }

    @Bean
    @Singleton
    public ParamConfMapper paramConfMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(ParamConfMapper.class);
    }

    @Bean
    @Singleton
    public InventoryItemMapper inventoryItemMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(InventoryItemMapper.class);
    }

    @Bean
    @Singleton
    public QuestStatusMapper questStatusMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(QuestStatusMapper.class);
    }

    @Bean
    @Singleton
    public QuestProgressMapper questProgressMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(QuestProgressMapper.class);
    }

    @Bean
    @Singleton
    public SkillMapper skillMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(SkillMapper.class);
    }

    @Bean
    @Singleton
    public BusOutboxMapper busOutboxMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(BusOutboxMapper.class);
    }

    @Bean
    @Singleton
    public BusStreamMapper busStreamMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(BusStreamMapper.class);
    }

    @Bean
    @Singleton
    public BuddyListMapper buddyListMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(BuddyListMapper.class);
    }

    @Bean
    @Singleton
    public ApiKeyMapper apiKeyMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(ApiKeyMapper.class);
    }

    @Bean
    @Singleton
    public ApiRequestAuditMapper apiRequestAuditMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(ApiRequestAuditMapper.class);
    }

    @Bean
    @Singleton
    public ToolExecutionAuditMapper toolExecutionAuditMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(ToolExecutionAuditMapper.class);
    }

    @Bean
    @Singleton
    public PointAccountMapper pointGameAccountRecordMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(PointAccountMapper.class);
    }

    @Bean
    @Singleton
    public SubscriptionPlanMapper subscriptionPlanMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(SubscriptionPlanMapper.class);
    }

    @Bean
    @Singleton
    public PointTransactionMapper pointTransactionMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(PointTransactionMapper.class);
    }

    @Bean
    @Singleton
    public AdminRoleMapper adminRoleMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AdminRoleMapper.class);
    }

    @Bean
    @Singleton
    public AccountAdminRoleMapper accountAdminRoleMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AccountAdminRoleMapper.class);
    }

    @Bean
    @Singleton
    public AdminSessionMapper adminSessionMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AdminSessionMapper.class);
    }

    @Bean
    @Singleton
    public AdminOperationAuditMapper adminOperationAuditMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AdminOperationAuditMapper.class);
    }

    @Bean
    @Singleton
    public ParamConfRepository paramConfRepository(ParamConfMapper mapper) {
        // M1 起替换 M0 的纯 JDBC 实现（JdbcParamConfRepository），接口不变
        return new FlexParamConfRepository(mapper);
    }

    @Bean
    @Singleton
    public GameAccountRepository accountRepository(GameAccountRecordMapper mapper) {
        return new FlexGameAccountRepository(mapper);
    }

    @Bean
    @Singleton
    public AccountDeletionRepository accountDeletionRepository(
            GameAccountRecordMapper accountMapper,
            PlayerCharacterRecordMapper characterMapper,
            InventoryItemMapper inventoryItemMapper,
            QuestStatusMapper questStatusMapper,
            QuestProgressMapper questProgressMapper,
            SkillMapper skillMapper,
            BuddyListMapper buddyListMapper,
            PointAccountMapper pointGameAccountRecordMapper,
            PointTransactionMapper pointTransactionMapper,
            AccountAdminRoleMapper accountAdminRoleMapper,
            AdminSessionMapper adminSessionMapper,
            ApiKeyMapper apiKeyMapper) {
        return new FlexAccountDeletionRepository(
                accountMapper, characterMapper, inventoryItemMapper, questStatusMapper,
                questProgressMapper, skillMapper, buddyListMapper, pointGameAccountRecordMapper,
                pointTransactionMapper, accountAdminRoleMapper, adminSessionMapper, apiKeyMapper);
    }

    @Bean
    @Singleton
    public PlayerCharacterRepository characterRepository(PlayerCharacterRecordMapper mapper) {
        return new FlexPlayerCharacterRepository(mapper);
    }

    @Bean
    @Singleton
    public InventoryItemRepository inventoryItemRepository(InventoryItemMapper mapper) {
        return new FlexInventoryItemRepository(mapper);
    }

    @Bean
    @Singleton
    public PlayerCharacterSnapshotRepository characterSnapshotRepository(PlayerCharacterRecordMapper characterMapper,
                                                                   InventoryItemMapper inventoryItemMapper,
                                                                   QuestStatusMapper questStatusMapper,
                                                                   QuestProgressMapper questProgressMapper,
                                                                   SkillMapper skillMapper) {
        return new FlexPlayerCharacterSnapshotRepository(
                characterMapper, inventoryItemMapper, questStatusMapper, questProgressMapper, skillMapper);
    }

    @Bean
    @Singleton
    public QuestRepository questRepository(QuestStatusMapper statusMapper, QuestProgressMapper progressMapper) {
        return new FlexQuestRepository(statusMapper, progressMapper);
    }

    @Bean
    @Singleton
    public SkillRepository skillRepository(SkillMapper mapper) {
        return new FlexSkillRepository(mapper);
    }

    @Bean
    @Singleton
    public OutboxRepository busOutboxRepository(BusOutboxMapper mapper, BusStreamMapper streamMapper) {
        return new FlexBusOutboxRepository(mapper, streamMapper);
    }

    @Bean
    @Singleton
    public BuddyListRepository buddyListRepository(BuddyListMapper mapper) {
        return new FlexBuddyListRepository(mapper);
    }

    @Bean
    @Singleton
    public ApiKeyRepository apiKeyRepository(ApiKeyMapper mapper) {
        return new FlexApiKeyRepository(mapper);
    }

    @Bean
    @Singleton
    public ApiRequestAuditRepository apiRequestAuditRepository(ApiRequestAuditMapper mapper) {
        return new FlexApiRequestAuditRepository(mapper);
    }

    @Bean
    @Singleton
    public ToolExecutionAuditRepository toolExecutionAuditRepository(ToolExecutionAuditMapper mapper) {
        return new FlexToolExecutionAuditRepository(mapper);
    }

    @Bean
    @Singleton
    public PointAccountRepository pointAccountRepository(PointAccountMapper mapper) {
        return new FlexPointAccountRepository(mapper);
    }

    @Bean
    @Singleton
    public SubscriptionPlanRepository subscriptionPlanRepository(SubscriptionPlanMapper mapper) {
        return new FlexSubscriptionPlanRepository(mapper);
    }

    @Bean
    @Singleton
    public PointTransactionRepository pointTransactionRepository(PointTransactionMapper mapper) {
        return new FlexPointTransactionRepository(mapper);
    }

    @Bean
    @Singleton
    public AdminRoleRepository adminRoleRepository(AdminRoleMapper mapper) {
        return new FlexAdminRoleRepository(mapper);
    }

    @Bean
    @Singleton
    public AccountAdminRoleRepository accountAdminRoleRepository(AccountAdminRoleMapper mapper) {
        return new FlexAccountAdminRoleRepository(mapper);
    }

    @Bean
    @Singleton
    public AdminSessionRepository adminSessionRepository(AdminSessionMapper mapper) {
        return new FlexAdminSessionRepository(mapper);
    }

    @Bean
    @Singleton
    public AdminOperationAuditRepository adminOperationAuditRepository(AdminOperationAuditMapper mapper) {
        return new FlexAdminOperationAuditRepository(mapper);
    }

}
