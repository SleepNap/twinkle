package org.gms.data.config;

import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.data.entity.ParamConf;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 实现的 param_conf 仓库（M0 生产用）。
 *
 * <p>架构 6.2 选定 MyBatis-Flex 作为 ORM，但 M0 阶段 SqlSessionFactory 装配还没接（M1 随业务表一起接）。
 * 配置中心（param_conf）是 L1 热更新地基，必须有可用实现——这里用纯 JDBC 落地，语义与 MyBatis-Flex
 * Mapper 一致（读全量 / 按 key 查 / upsert）。
 *
 * <p>M1 接入 MyBatis-Flex 后，本类替换为 Mapper 实现，{@link DbConfigFacade} 依赖的是
 * {@link ParamConfRepository} 接口，业务代码零改动。
 *
 * <p>SQLite 三件套中的"单写连接"由 {@link org.gms.data.SimpleDriverDataSource} + 上层 SingleWriteExecutor
 * 保证（M1 落地）；本类只做数据访问。
 */
@Singleton
public class JdbcParamConfRepository implements ParamConfRepository {

    private static final Logger LOG = LogManager.getLogger(JdbcParamConfRepository.class);

    private final DataSource dataSource;

    public JdbcParamConfRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ParamConf> selectAll() {
        List<ParamConf> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, config_key, config_value, version, updated_at FROM param_conf");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rowTo(rs));
            }
        } catch (SQLException e) {
            // param_conf 表可能尚未建（迁移在 DataSourceFactory 创建时跑，但防御性兜底）。
            // "no such table" 属于启动顺序问题：返回空，等迁移后 signalChange 重读即可。
            if (isMissingTable(e)) {
                LOG.warn("param_conf 表尚不存在（迁移未跑），返回空配置集");
                return List.of();
            }
            LOG.error("查询 param_conf 全部失败", e);
            throw new IllegalStateException("查询 param_conf 失败", e);
        }
        return list;
    }

    @Override
    public Optional<ParamConf> selectByKey(String configKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, config_key, config_value, version, updated_at FROM param_conf WHERE config_key = ?")) {
            ps.setString(1, configKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rowTo(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            LOG.error("查询 param_conf 失败: key={}", configKey, e);
            throw new IllegalStateException("查询 param_conf 失败", e);
        }
    }

    @Override
    public void insert(ParamConf entity) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO param_conf (config_key, config_value, version, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, entity.getConfigKey());
            ps.setString(2, entity.getConfigValue());
            ps.setLong(3, entity.getVersion());
            ps.setString(4, entity.getUpdatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("插入 param_conf 失败: key={}", entity.getConfigKey(), e);
            throw new IllegalStateException("插入 param_conf 失败", e);
        }
    }

    @Override
    public void update(ParamConf entity) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE param_conf SET config_value=?, version=?, updated_at=? WHERE config_key=?")) {
            ps.setString(1, entity.getConfigValue());
            ps.setLong(2, entity.getVersion());
            ps.setString(3, entity.getUpdatedAt());
            ps.setString(4, entity.getConfigKey());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("更新 param_conf 失败: key={}", entity.getConfigKey(), e);
            throw new IllegalStateException("更新 param_conf 失败", e);
        }
    }

    private static ParamConf rowTo(ResultSet rs) throws SQLException {
        ParamConf p = new ParamConf();
        p.setId(rs.getLong("id"));
        p.setConfigKey(rs.getString("config_key"));
        p.setConfigValue(rs.getString("config_value"));
        p.setVersion(rs.getLong("version"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
    }

    /** 判定"表不存在"类错误（SQLite / PG / MySQL 各自的措辞）。 */
    private static boolean isMissingTable(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no such table")       // SQLite
                || msg.contains("does not exist")  // PostgreSQL: relation "xxx" does not exist
                || msg.contains("table") && msg.contains("doesn't exist"); // MySQL
    }
}
