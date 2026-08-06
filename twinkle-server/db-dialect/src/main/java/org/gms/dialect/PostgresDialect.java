package org.gms.dialect;

import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * PostgreSQL 方言实现（大服 / 分布式）。
 *
 * <h2>特殊点</h2>
 * <ul>
 *   <li><b>BOOLEAN</b>：原生 BOOL，用 {@code TRUE}/{@code FALSE} 字面量。</li>
 *   <li><b>自增主键</b>：标准 {@code SERIAL} / {@code GENERATED ALWAYS AS IDENTITY}；回写走
 *       {@code RETURNING id}（可选加速）。</li>
 *   <li><b>时间</b>：{@code CURRENT_TIMESTAMP} 返回 {@code timestamp with time zone}。</li>
 * </ul>
 */
@Singleton
public final class PostgresDialect implements DbDialect {

    @Override
    public DialectId id() {
        return DialectId.POSTGRESQL;
    }

    @Override
    public Optional<String> generatedKeyReturningClause() {
        return Optional.of("RETURNING id");
    }

    @Override
    public String booleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }
}
