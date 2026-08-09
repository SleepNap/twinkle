package org.gms.data;

import com.mybatisflex.core.MybatisFlexBootstrap;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.data.config.FlexParamConfRepository;
import org.gms.data.config.ParamConfRepository;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.AiUsageMapper;
import org.gms.data.mapper.BuddyListMapper;
import org.gms.data.mapper.BusOutboxMapper;
import org.gms.data.mapper.BusStreamMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.ParamConfMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.data.repo.BuddyListRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexAiUsageRepository;
import org.gms.data.repo.FlexBuddyListRepository;
import org.gms.data.repo.FlexBusOutboxRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.data.repo.FlexInventoryItemRepository;
import org.gms.data.repo.FlexQuestRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.QuestRepository;
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
        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(dataSource);
        bootstrap.addMapper(ParamConfMapper.class);
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.addMapper(CharacterMapper.class);
        // M3-5 存档表（进图回填/下线落库；M3-1 HTTP 查存档也依赖，统一在此注册）
        bootstrap.addMapper(InventoryItemMapper.class);
        bootstrap.addMapper(QuestStatusMapper.class);
        bootstrap.addMapper(QuestProgressMapper.class);
        bootstrap.addMapper(AiUsageMapper.class);
        bootstrap.addMapper(BusOutboxMapper.class);
        bootstrap.addMapper(BusStreamMapper.class);
        bootstrap.addMapper(BuddyListMapper.class);
        bootstrap.start();
        log.info("MyBatis-Flex 装配完成：ParamConf/Account/Character/InventoryItem/QuestStatus/QuestProgress/AiUsage/BusOutbox/BusStream/BuddyList 十个 Mapper 已注册");
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
    public QuestRepository questRepository(QuestStatusMapper statusMapper, QuestProgressMapper progressMapper) {
        return new FlexQuestRepository(statusMapper, progressMapper);
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
}
