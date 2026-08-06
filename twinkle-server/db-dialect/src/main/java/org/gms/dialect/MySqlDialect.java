package org.gms.dialect;

import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * MySQL / MariaDB 方言实现（兼容切换）。
 *
 * <h2>特殊点</h2>
 * <ul>
 *   <li><b>BOOLEAN</b>：MySQL 用 {@code TINYINT(1)} 模拟，{@code TRUE}/{@code FALSE} 是关键字字面量。</li>
 *   <li><b>自增主键</b>：{@code AUTO_INCREMENT}；回走 JDBC {@code Statement.getGeneratedKeys()} 或
 *       {@code LAST_INSERT_ID()} 函数。</li>
 *   <li><b>时间</b>：{@code NOW()} 返回 datetime 字符串；SQL 片段常混用 {@code CURRENT_TIMESTAMP}。</li>
 * </ul>
 */
@Singleton
public final class MySqlDialect implements DbDialect {

    @Override
    public DialectId id() {
        return DialectId.MYSQL;
    }

    @Override
    public Optional<String> generatedKeyReturningClause() {
        return Optional.empty();
    }

    @Override
    public String booleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }
}
