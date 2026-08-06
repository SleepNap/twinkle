package org.gms.data.mapper;

import com.mybatisflex.core.BaseMapper;
import org.gms.data.entity.ParamConf;

/**
 * MyBatis-Flex Mapper（架构 6.2 选定 ORM，M1 接入 SqlSessionFactory 后使用）。
 *
 * <p>BaseMapper 提供 selectList / selectOne / insert / updateById 等基础 CRUD。
 * SQL 差异点（如禁未封禁查询的 {@code banned <> 1}，红线 8）进 {@code db-dialect} 模块，不在 Mapper 里硬编码。
 */
public interface ParamConfMapper extends BaseMapper<ParamConf> {
    // M1 阶段接入 SqlSessionFactory 后，用 MyBatis-Flex 实现 ParamConfRepository。
}
