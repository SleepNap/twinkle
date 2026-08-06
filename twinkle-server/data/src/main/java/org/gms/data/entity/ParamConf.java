package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * 配置表实体（架构 4.6.5 / 5.2 L1：配置中心 = DB 真值 + 版本号）。
 *
 * <p>字段对齐参考项目 {@code param_conf}（思路参考自 BeiDou-Server：
 * 架构上"配置 DB 真值"是 L1 热更新地基的入口，但表结构与查询走自研，不复制参考项目实现）。
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code configKey}：配置键（点命名空间，如 {@code game.level.rate}）。</li>
 *   <li>{@code configValue}：字符串值（任何类型都序列化为字符串，读取时按需转换）。</li>
 *   <li>{@code version}：单调递增版本号，每次变更 +1；与
 *       {@link org.gms.config.ConfigChangeEvent#version()} 对应。</li>
 *   <li>{@code updatedAt}：UTC 秒级时间戳（SQLite 用 TEXT 存 ISO 8601）。</li>
 * </ul>
 */
@Table("param_conf")
public class ParamConf {

    @Id(keyType = KeyType.Auto)
    private Long id;

    private String configKey;
    private String configValue;
    private long version;
    private String updatedAt;

    public ParamConf() {
    }

    public ParamConf(String configKey, String configValue) {
        this.configKey = configKey;
        this.configValue = configValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
