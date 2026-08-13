package org.gms.data;

import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.MybatisFlexBootstrap;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.data.config.FlexParamConfRepository;
import org.gms.data.config.ParamConfRepository;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.ApiKeyMapper;
import org.gms.data.mapper.ApiRequestAuditMapper;
import org.gms.data.mapper.AiUsageMapper;
import org.gms.data.mapper.BuddyListMapper;
import org.gms.data.mapper.BusOutboxMapper;
import org.gms.data.mapper.BusStreamMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.ParamConfMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.mapper.SkillMapper;
import org.gms.data.mapper.ToolExecutionAuditMapper;
import org.gms.data.mapper.ModelRateMapper;
import org.gms.data.mapper.PointAccountMapper;
import org.gms.data.mapper.PointTransactionMapper;
import org.gms.data.mapper.SubscriptionPlanMapper;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.ApiKeyRepository;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.data.repo.BuddyListRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.CharacterSnapshotRepository;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexApiKeyRepository;
import org.gms.data.repo.FlexApiRequestAuditRepository;
import org.gms.data.repo.FlexAiUsageRepository;
import org.gms.data.repo.FlexBuddyListRepository;
import org.gms.data.repo.FlexBusOutboxRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.data.repo.FlexCharacterSnapshotRepository;
import org.gms.data.repo.FlexInventoryItemRepository;
import org.gms.data.repo.FlexQuestRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.QuestRepository;
import org.gms.data.repo.FlexSkillRepository;
import org.gms.data.repo.SkillRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.data.repo.FlexToolExecutionAuditRepository;
import org.gms.data.repo.ModelRateRepository;
import org.gms.data.repo.PointAccountRepository;
import org.gms.data.repo.PointTransactionRepository;
import org.gms.data.repo.SubscriptionPlanRepository;
import org.gms.data.repo.FlexModelRateRepository;
import org.gms.data.repo.FlexPointAccountRepository;
import org.gms.data.repo.FlexPointTransactionRepository;
import org.gms.data.repo.FlexSubscriptionPlanRepository;
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
@Log4j2
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
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.addMapper(CharacterMapper.class);
        // M3-5 存档表（进图回填/下线落库；M3-1 HTTP 查存档也依赖，统一在此注册）
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.addMapper(SkillMapper.class);
        bootstrap.addMapper(AiUsageMapper.class);
        bootstrap.addMapper(BusOutboxMapper.class);
        bootstrap.addMapper(BusStreamMapper.class);
        bootstrap.addMapper(BuddyListMapper.class);
        bootstrap.addMapper(ApiKeyMapper.class);
        bootstrap.addMapper(ApiRequestAuditMapper.class);
        bootstrap.addMapper(ToolExecutionAuditMapper.class);
        bootstrap.addMapper(PointAccountMapper.class);
        bootstrap.addMapper(ModelRateMapper.class);
        bootstrap.addMapper(SubscriptionPlanMapper.class);
        bootstrap.addMapper(PointTransactionMapper.class);
        bootstrap.start();
        log.info("MyBatis-Flex 装配完成：十八个 Mapper 已注册（含技能、Credential、HTTP 审计、Tool 审计与积分计费）");
        return bootstrap;
    }

    @Bean
    @Singleton
    public AccountMapper accountMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AccountMapper.class);
    }

    @Bean
    @Singleton
    public CharacterMapper characterMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(CharacterMapper.class);
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
    public AiUsageMapper aiUsageMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(AiUsageMapper.class);
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
    public PointAccountMapper pointAccountMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(PointAccountMapper.class);
    }

    @Bean
    @Singleton
    public ModelRateMapper modelRateMapper(MybatisFlexBootstrap bootstrap) {
        return bootstrap.getMapper(ModelRateMapper.class);
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
    public ParamConfRepository paramConfRepository(ParamConfMapper mapper) {
        // M1 起替换 M0 的纯 JDBC 实现（JdbcParamConfRepository），接口不变
        return new FlexParamConfRepository(mapper);
    }

    @Bean
    @Singleton
    public AccountRepository accountRepository(AccountMapper mapper) {
        return new FlexAccountRepository(mapper);
    }

    @Bean
    @Singleton
    public CharacterRepository characterRepository(CharacterMapper mapper) {
        return new FlexCharacterRepository(mapper);
    }

    @Bean
    @Singleton
    public InventoryItemRepository inventoryItemRepository(InventoryItemMapper mapper) {
        return new FlexInventoryItemRepository(mapper);
    }

    @Bean
    @Singleton
    public CharacterSnapshotRepository characterSnapshotRepository(CharacterMapper characterMapper,
                                                                   InventoryItemMapper inventoryItemMapper,
                                                                   QuestStatusMapper questStatusMapper,
                                                                   QuestProgressMapper questProgressMapper,
                                                                   SkillMapper skillMapper) {
        return new FlexCharacterSnapshotRepository(
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
    public AiUsageRepository aiUsageRepository(AiUsageMapper mapper) {
        return new FlexAiUsageRepository(mapper);
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
    public ModelRateRepository modelRateRepository(ModelRateMapper mapper) {
        return new FlexModelRateRepository(mapper);
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
}
