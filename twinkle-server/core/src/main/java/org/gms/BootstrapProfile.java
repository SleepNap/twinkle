package org.gms;

import java.util.Locale;

/**
 * 进程装配档位（架构 4.1、4.6）。
 *
 * <p>进程边界是配置，不是硬编码。同一套代码按 {@code --profile} 决定哪些角色模块装配到一个 JVM。
 *
 * <p>进程边界 = 配置的物理体现：单进程共享一份内存，跨进程走网络 + 共享状态单一属主。
 *
 * <h2>档位对照</h2>
 * <ul>
 *   <li>{@link #SINGLE}（默认）：全部内嵌，一份内存，零开销，2C2G 强制档。</li>
 *   <li>{@link #STANDALONE}：同 SINGLE，预留区分未来「嵌入式精简版」演化路径。</li>
 *   <li>{@link #SPLIT_CHANNEL}：管理进程 + 每频道 1 个进程，故障隔离立等可取，代价是 N+1 JVM 常驻开销。</li>
 *   <li>{@link #SPLIT_REALM}：管理进程 + 每大区 1 进程，多机/多区部署形态。</li>
 * </ul>
 */
public enum BootstrapProfile {
    SINGLE,
    STANDALONE,
    SPLIT_CHANNEL,
    SPLIT_REALM;

    /**
     * 解析命令行值。宽容：忽略大小写、忽略横线/下划线。未知值按默认 {@link #SINGLE} 处理（不抛异常，
     * 因为 2C2G 强制单进程是红线 15）。
     */
    public static BootstrapProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SINGLE;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        for (BootstrapProfile p : values()) {
            if (p.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return p;
            }
        }
        return SINGLE;
    }

    /**
     * 是否单进程（一组角色共享一份内存）。2C2G 红线 15 强制单进程。
     */
    public boolean isSingleProcess() {
        return this == SINGLE || this == STANDALONE;
    }
}
