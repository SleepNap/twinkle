package org.gms.hotreload.versioned;

import java.util.concurrent.atomic.AtomicLong;
import org.gms.i18n.I18n;

/**
 * 版本门默认实现：{@link AtomicLong} 维护当前逻辑版本。
 *
 * <p>初始版本 {@value #INITIAL_VERSION}；每次 L3 热重载换代（{@link #onReload()}）递增。
 * 版本判定必须原子——游戏 tick 单线程，但迟到的旧逻辑写可能在写线程 / 存档线程落盘，
 * 与换代并发，读-判-放行不能有竞态。
 */
public final class DefaultVersionGate implements VersionGate {

    /** 初始逻辑版本（首版逻辑）。 */
    public static final long INITIAL_VERSION = 1L;

    private final AtomicLong currentVersion = new AtomicLong(INITIAL_VERSION);

    @Override
    public long currentVersion() {
        return currentVersion.get();
    }

    @Override
    public VersionDecision decide(Versioned operation) {
        return decide(operation.logicVersion());
    }

    @Override
    public VersionDecision decide(long writeVersion) {
        long now = currentVersion.get();
        if (writeVersion == now) {
            return VersionDecision.ALLOW;
        }
        if (writeVersion < now) {
            // 迟到写：旧逻辑发起、换代后才落盘。业务方据此丢弃或重放。
            return VersionDecision.STALE;
        }
        // 未来版本：只能来自版本号被回退/写入口用错版本，属编程错误，显式暴露而非静默放行。
        throw new IllegalStateException(
                I18n.message("error.version_gate.future_version", writeVersion, now));
    }

    @Override
    public long onReload() {
        return currentVersion.incrementAndGet();
    }
}
