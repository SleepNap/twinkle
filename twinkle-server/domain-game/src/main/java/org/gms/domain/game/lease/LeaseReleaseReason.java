package org.gms.domain.game.lease;

/**
 * 怪物控制权释放原因（事故报告 §七 可观测字段）。
 *
 * <p>只有 {@link #LEASE_EXPIRED}（租约超时主动释放）进入短冷却；怪物死亡/消失/换图/
 * 代际替换均自然解除归属，不进冷却、不累计异常、不警告（报告 §4.3 硬验收）。
 */
public enum LeaseReleaseReason {
    /** 租约超时（最后有效 MOVE_LIFE 距今超过 TTL），主动释放控制权。 */
    LEASE_EXPIRED,
    /** 怪物被击杀（死亡）。 */
    MONSTER_DIED,
    /** 怪物自然消失/地图卸载清除。 */
    DESPAWNED,
    /** 控制者离开地图/断链。 */
    MAP_LEFT,
    /** 新连接认领同一角色（新代际），旧代际租约立即失效。 */
    SESSION_REPLACED
}
