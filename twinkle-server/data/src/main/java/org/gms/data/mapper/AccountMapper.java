package org.gms.data.mapper;

import com.mybatisflex.core.BaseMapper;
import org.gms.data.entity.Account;

/**
 * 账号表 Mapper（架构 M1 登录，MyBatis-Flex）。
 *
 * <p>BaseMapper 提供基础 CRUD。封禁语义 {@code banned <> 1}（红线 8）由业务层
 * 基于实体字段判断，不进 Mapper（保持查询语义清晰）。
 */
public interface AccountMapper extends BaseMapper<Account> {
}
