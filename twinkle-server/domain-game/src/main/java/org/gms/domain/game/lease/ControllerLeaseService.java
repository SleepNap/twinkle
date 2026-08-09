package org.gms.domain.game.lease;

/**
 * 怪物控制租约服务接口（事故报告阶段 B / §5.5：租约状态放稳定层，按 sessionGeneration
 * 归属；可重载逻辑只提交"已验证 MOVE_LIFE"的续租命令）。
 *
 * <p>放 domain-game 稳定层：channel（可替换 handler）经本接口申请/续租/释放控制权，
 * 不引实现类（红线 8）；怪物死亡/消失的稳定状态变更同步解除归属，不能依赖某个可重载
 * handler 最后补清理（报告 §5.5-3）。接口不假设进程内外（铁律 1）。
 *
 * <p>线程模型：renew/claim/release 来自 Netty IO 线程（并发）；sweep 来自单一游戏 tick
 * 线程。实现用单一临界区包住读-校验-写回。
 */
public interface ControllerLeaseService {

    /**
     * 新连接认领同一角色：写当前代际投影，并把该角色旧代际持有的全部租约以
     * {@link LeaseReleaseReason#SESSION_REPLACED} 立即释放。
     */
    void onClaim(long characterId, long sessionId, long generation);

    /**
     * 断链回调（仅当前代际有效）：清投影 + 以 {@link LeaseReleaseReason#MAP_LEFT}
     * 释放该 owner 全部租约。非当前代际 no-op（旧连接迟到关闭）。
     */
    void onDisconnect(long characterId, long sessionId, long generation);

    /**
     * 申请控制一只怪物（SPAWN_MONSTER_CONTROL 分配前调用）。
     *
     * @return 是否成功接管（怪物无主 或 现持有者代际已陈旧，且本人不在冷却）。
     */
    boolean tryClaim(int mapId, int monsterOid, LeaseOwner owner);

    /**
     * MOVE_LIFE 续租（可重载 handler 在完成归属校验后调用）。
     *
     * <p><b>fail-closed</b>：怪在存 + owner 匹配 + 代际==当前投影，任一不过返回 false，
     * 调用方必须丢弃整包（不广播移动、不续租）——防伪造/迟到/越权续租养活坏控制者。
     */
    boolean renew(int mapId, int monsterOid, LeaseOwner owner);

    /**
     * 释放控制权（怪物死亡/消失/地图卸载/控制权转移时由稳定状态变更调用）。
     */
    void release(int mapId, int monsterOid, LeaseReleaseReason reason);

    /** 该怪物当前是否无主（无人控制）。 */
    boolean isUnowned(int mapId, int monsterOid);

    /** 该角色（characterId）是否处于控制权短冷却。 */
    boolean isInCooldown(long characterId);

    /** 该 owner 当前受控的活怪数（观测，报告 §七）。 */
    int controlledAliveCount(LeaseOwner owner);

    /**
     * 周期巡检（挂在游戏 tick 上，不新增线程）：过期租约释放 + 冷却 +
     * 防御性代际回收 + tick 暂停宽限。
     */
    void sweep(long nowNanos);

    /** 观测快照（报告 §七：受控活怪数、释放原因、续租拒绝）。 */
    LeaseStats stats();
}
