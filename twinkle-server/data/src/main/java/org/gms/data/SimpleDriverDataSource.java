package org.gms.data;

import org.gms.i18n.I18n;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * 最小嵌入式 DataSource（基于 DriverManager）。
 *
 * <p>不为 2C2G 档引入 HikariCP 这样的池化库（架构红线 9.1：池化库的常驻内存不容忽视）。
 * 写入走单写执行器（{@code SingleWriteExecutor}，M0 后续实现），读短期直接 DriverManager。
 * 大服档之后再按需加 HikariCP。
 *
 * <p>用途：M0 阶段只用于 (a) 启动期迁移 (b) DbConfigFacade 启动期加载 param_conf。
 * 运行期不做热路径访问（架构 1.3：内存态是权威，DB 只是持久化 + 查询层）。
 */
public final class SimpleDriverDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    public SimpleDriverDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        // 显式注册 JDBC 驱动（避免运行期 Class.forName 时机问题）：
        // SQLite 走 org.sqlite.JDBC，Postgres 走 org.postgresql.Driver，MySQL 走 com.mysql.cj.jdbc.Driver
        registerDriver(url);
    }

    private static void registerDriver(String url) {
        try {
            if (url.startsWith("jdbc:sqlite:")) {
                Class.forName("org.sqlite.JDBC");
            } else if (url.startsWith("jdbc:postgresql:")) {
                Class.forName("org.postgresql.Driver");
            } else if (url.startsWith("jdbc:mysql:")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(I18n.message("error.data.jdbc_driver_not_found", url), e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    // 以下方法在 M0 阶段不被调用，置为不支持（避免误用）。
    @Override public PrintWriter getLogWriter() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }
    @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return java.util.logging.Logger.getLogger("global");
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
}
