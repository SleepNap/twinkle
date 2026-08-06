package org.gms.data.config;

import org.gms.data.entity.ParamConf;

import java.util.List;
import java.util.Optional;

/**
 * param_conf 数据仓库接口（架构 6.2 选定 MyBatis-Flex + Repository 模式）。
 *
 * <p>业务代码（{@link DbConfigFacade} 等）只依赖此接口，不依赖 MyBatis-Flex具体类。
 * 后续 M1 起真实业务表的 CRUD 也遵循此模式（Mapper 实现 + Repository 接口）。
 *
 * <p>M0 阶段：一个实现 {@link JdbcParamConfRepository}（纯 JDBC，语义与 MyBatis-Flex Mapper 一致），
 * M1 接入 SqlSessionFactory 后替换实现，接口不变。
 */
public interface ParamConfRepository {

    /**
     * 拉全部配置（启动期一次性加载）。
     */
    List<ParamConf> selectAll();

    /**
     * 按 key 查（用于 upsert 决策）。
     */
    Optional<ParamConf> selectByKey(String configKey);

    /**
     * 插入新配置。
     */
    void insert(ParamConf entity);

    /**
     * 更新已有配置（按 config_key 定位）。
     */
    void update(ParamConf entity);
}
