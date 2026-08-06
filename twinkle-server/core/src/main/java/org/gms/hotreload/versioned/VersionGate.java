package org.gms.hotreload.versioned;

/**
 * 版本门：热重载写路径的统一入口（架构 5.3，M0/M1 定稿、M2 按此编写）。
 *
 * <p>职责：维护"当前逻辑版本"，判定一次写操作是否跨越了热重载换代。等价判断——
 * 重载后旧逻辑的迟到写（{@link VersionDecision#STALE}）要被识别出来，由业务方
 * 决定丢弃或重放。这是防复制 bug（架构 5.3）的机制前提。
 *
 * <p>与 {@link RestartCoordinator} 的分工：RestartCoordinator 管"进程级 L4 重启编排"，
 * 本接口管"逻辑级 L3 热替换的换代判定"——L3 每次换代替换新 classloader 时调用
 * {@link #onReload()} 递增版本，旧逻辑产生的写随后被版本门挡下。
 */
public interface VersionGate {

    /** 当前逻辑版本号（L3 重载换代后 +1）。 */
    long currentVersion();

    /** 判定一个实现了 {@link Versioned} 的写操作。 */
    VersionDecision decide(Versioned operation);

    /** 判定一次携带逻辑版本的写操作。 */
    VersionDecision decide(long writeVersion);

    /**
     * L3 热重载换代后调用：当前版本 +1。
     *
     * @return 新版本号
     */
    long onReload();
}
