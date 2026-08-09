package org.gms.dialect;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;

/**
 * 方言注册表（架构 6.3：运行时按配置选）。
 *
 * <p>三个方言实现都由 Micronaut DI 注入，对外暴露统一的 {@link DbDialect} 接口。
 * 业务代码只看到 {@link DbDialect}，不知道当前是 SQLite 还是 PG——这是"业务代码不出现裸方言差异"
 * 的执行底盘。
 */
@Singleton
@Log4j2
public final class DbDialectRegistry {



    private final Map<DbDialect.DialectId, DbDialect> dialects;

    public DbDialectRegistry(List<DbDialect> all) {
        this.dialects = all.stream()
                .collect(Collectors.toUnmodifiableMap(DbDialect::id, d -> d));
        if (dialects.size() < 3) {
            log.warn("方言注册表不完整（注册了 {} 个）", dialects.size());
        }
    }

    /**
     * 按配置 ID 选方言。未知 ID 时降级 SQLite（低配默认，与架构 6.2 一致）。
     */
    public DbDialect resolve(DbDialect.DialectId id) {
        return dialects.getOrDefault(id, dialects.get(DbDialect.DialectId.SQLITE));
    }

    /**
     * 按 JDBC URL 前缀自动识别。{@code jdbc:sqlite:} → SQLite；{@code jdbc:postgresql:} → PostgreSQL；
     * {@code jdbc:mysql:} → MySQL。无法识别返回 SQLite（低配默认）。
     */
    public DbDialect resolveByUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return dialects.get(DbDialect.DialectId.SQLITE);
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:sqlite:")) {
            return dialects.get(DbDialect.DialectId.SQLITE);
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return dialects.get(DbDialect.DialectId.POSTGRESQL);
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return dialects.get(DbDialect.DialectId.MYSQL);
        }
        return dialects.get(DbDialect.DialectId.SQLITE);
    }
}
