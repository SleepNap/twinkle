package org.gms.data.repo;

import org.gms.data.entity.Account;

import java.util.Optional;

/**
 * 账号仓库（架构 M1 登录校验）。
 *
 * <p>接口化存储：M1 用 MyBatis-Flex 实现，后续可替换存储细节而不影响业务。
 */
public interface AccountRepository {

    /**
     * 按账号名查询。不存在返回 {@link Optional#empty()}。
     */
    Optional<Account> findByName(String name);

    /**
     * 更新账号（登录前置流程落库用：接受服务条款 tos、设置性别 gender 等）。
     */
    void update(Account account);
}
