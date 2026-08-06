package org.gms.data;

import com.mybatisflex.core.MybatisFlexBootstrap;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.data.config.FlexParamConfRepository;
import org.gms.data.config.ParamConfRepository;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.ParamConfMapper;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;

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

    private static final Logger LOG = LogManager.getLogger(MyBatisFlexFactory.class);

    @Bean
    @Singleton
    @Context
    public MybatisFlexBootstrap flexBootstrap(DataSource dataSource) {
        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setDataSource(dataSource);
        bootstrap.addMapper(ParamConfMapper.class);
        bootstrap.addMapper(AccountMapper.class);
        bootstrap.addMapper(CharacterMapper.class);
        bootstrap.start();
        LOG.info("MyBatis-Flex 装配完成：ParamConf/Account/Character 三个 Mapper 已注册");
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
}
