package org.gms.dialect;

import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * SQLite 方言实现（低配 standalone 默认）。
 *
 * <h2>特殊点</h2>
 * <ul>
 *   <li><b>BOOLEAN</b>：SQLite 没有原生 BOOL，内部使用 INTEGER 0/1。查询未封禁走
 *       {@code banned <> 1}（兼容 NULL），由业务层保证（红线 8）。</li>
 *   <li><b>自增主键</b>：走 {@code INTEGER PRIMARY KEY} + 自动 rowid；无需 {@code RETURNING}。</li>
 *   <li><b>时间</b>：{@code CURRENT_TIMESTAMP} 走 UTC 文本，{@code CURRENT_TIMESTAMP} 返回秒级。
 *       推荐持久化时存为 ISO 8601 字符串（{@code TEXT}），跨数据库读出后转换。</li>
 * </ul>
 */
@Singleton
public final class SqliteDialect implements DbDialect {

    @Override
    public DialectId id() {
        return DialectId.SQLITE;
    }

    @Override
    public Optional<String> generatedKeyReturningClause() {
        // SQLite 不支持 RETURNING 子句，使用 last_insert_rowid()。
        return Optional.empty();
    }

    @Override
    public String booleanLiteral(boolean value) {
        // SQLite 用 0/1 INTEGER，不接受 TRUE/FALSE。
        return value ? "1" : "0";
    }
}
