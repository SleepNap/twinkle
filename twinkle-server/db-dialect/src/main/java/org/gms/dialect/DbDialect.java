package org.gms.dialect;

import java.util.Optional;

/**
 * 方言差异点抽象（架构 6.3：业务代码禁止出现裸方言差异）。
 *
 * <p>封装所有同语义、不同写法的点：自增主键、upsert、布尔值、时间函数、字符串函数等。
 *
 * <p>三个实现：{@link SqliteDialect}、{@link PostgresDialect}、{@link MySqlDialect}。
 * 运行时由 {@link DialectRegistry} 按配置 {SQLite / PostgreSQL / MySQL} 选择。
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>所有方法必须返回「该方言下安全的标准 SQL 片段」（不含具体值，参数走 {@code ?}）。</li>
 *   <li>不允许"如果方言是 X 则拼接 Y"——业务代码拿到 {@link DbDialect} 实例调用即可，方言选择由
 *       装配层完成（架构 4.6.2）。</li>
 *   <li>复杂 SQL（SELECT/JOIN/CTE/窗口）走「两库公共子集」直接写，不必进方言抽象。</li>
 * </ul>
 */
public interface DbDialect {

    /** 方言身份。配置 {@code twinkle.db.dialect} 直接对应。 */
    DialectId id();

    /**
     * 用于 {code INSERT INTO ... ON ...} 类自增主键回写的最小语句片段（可选）。
     * 例如 PostgreSQL 的 {@code RETURNING id}；SQLite 可返回 {@code last_insert_rowid()} 函数调用。
     *
     * <p>当实现无需特殊片段时返回 {@link Optional#empty()}，由上游走默认 {@code Statement.getGeneratedKeys}。
     */
    Optional<String> generatedKeyReturningClause();

    /**
     * 必须显式声明：默认 schema（用于多 schema 库；空为默认 db）。
     */
    default String defaultSchema() {
        return "";
    }

    /**
     * 当前时间戳函数。SQLite/PostgreSQL 用 {@code CURRENT_TIMESTAMP}；MySQL 习惯用 {@code NOW()}。
     */
    default String currentTimestampFn() {
        return "CURRENT_TIMESTAMP";
    }

    /**
     * BOOLEAN 字面量写法。SQLite 内部用 INTEGER 0/1（红线 8：{@code banned <> 1}），Postgres/MySQL 用 TRUE/FALSE。
     */
    String booleanLiteral(boolean value);

    /**
     * 枚举身份。覆盖 {@link Enum#name()} 以便跨方言配置使用。
     */
    enum DialectId {
        SQLITE,
        POSTGRESQL,
        MYSQL
    }
}
