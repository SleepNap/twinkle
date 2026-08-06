package org.gms.hotreload.versioned;

/**
 * 携带逻辑版本的写操作（红线 12：可替换层不持有跨操作状态）。
 *
 * <p>版本门契约（架构 5.3，M0/M1 定稿、M2 按此编写）：写路径统一携带"产生它的逻辑版本号"。
 * L3 热重载换代后新逻辑版本号递增，迟到的旧逻辑写操作经 {@link VersionGate#decide(long)}
 * 识别为 {@link VersionDecision#STALE} 后丢弃或重放——绝不静默放行，否则产生复制 bug。
 *
 * <p>实现方：一切可能"跨越热重载边界落盘"的写操作/写请求。返回创建时的逻辑版本，
 * 由 {@link VersionGate} 签发与校验。
 */
public interface Versioned {

    /** 创建该写操作时的逻辑版本号。 */
    long logicVersion();
}
