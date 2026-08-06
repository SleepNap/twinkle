package org.gms.data.config;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.ParamConf;
import org.gms.data.mapper.ParamConfMapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的 param_conf 仓库（架构 6.2：选定 ORM，M1 接入 SqlSessionFactory 后
 * 替换 M0 的纯 JDBC 实现，接口 {@link ParamConfRepository} 不变）。
 *
 * <p>Bean 由 {@link org.gms.data.MyBatisFlexFactory} 统一装配（避免 @Singleton 自动发现
 * 与 Factory @Bean 双份产生歧义）。{@code configKey} 驼峰字段经 MyBatis-Flex 默认
 * 下划线转换映射到 {@code config_key} 列。
 */
public class FlexParamConfRepository implements ParamConfRepository {

    private final ParamConfMapper mapper;

    public FlexParamConfRepository(ParamConfMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ParamConf> selectAll() {
        return mapper.selectAll();
    }

    @Override
    public Optional<ParamConf> selectByKey(String configKey) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(ParamConf::getConfigKey).eq(configKey)));
    }

    @Override
    public void insert(ParamConf entity) {
        // insertSelective：只插已设置字段，其余列用 DB DEFAULT
        mapper.insertSelective(entity);
    }

    @Override
    public void update(ParamConf entity) {
        // update(entity) 按主键 id 定位（upsert 走 selectByKey 拿到的是带 id 的行）
        mapper.update(entity);
    }
}
