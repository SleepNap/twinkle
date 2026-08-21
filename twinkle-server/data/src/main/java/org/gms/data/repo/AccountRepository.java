package org.gms.data.repo;

import org.gms.data.entity.Account;

import java.util.List;
import java.util.Optional;

/**
 * 账号仓库（架构 M1 登录校验）。
 *
 * <p>接口化存储：M1 用 MyBatis-Flex 实现，后续可替换存储细节而不影响业务。
 */
public interface AccountRepository {

    /** 控制台账号分页结果。 */
    record AccountPage(long total, int offset, int limit, List<Account> records) {
    }

    /**
     * 按账号名查询。不存在返回 {@link Optional#empty()}。
     */
    Optional<Account> findByName(String name);

    /**
     * 按主键查询。不存在返回 {@link Optional#empty()}。
     */
    Optional<Account> findById(Long id);

    /**
     * 新建账号（bootstrap 管理员用）。
     */
    void insert(Account account);

    /**
     * 更新账号（登录前置流程落库用：接受服务条款 tos、设置性别 gender 等）。
     */
    void update(Account account);

    /**
     * 按账号名模糊搜索（供签发 key 时批量选择账号）。
     */
    List<Account> findByNameLike(String query, int limit);

    /**
     * 管理控制台分页检索。{@code banned == null} 表示全部，true/false 分别表示封禁/正常。
     */
    default AccountPage findPage(String query, Boolean banned, int offset, int limit) {
        List<Account> records = findByNameLike(query, Math.max(1, limit));
        return new AccountPage(records.size(), 0, limit, records);
    }
}
