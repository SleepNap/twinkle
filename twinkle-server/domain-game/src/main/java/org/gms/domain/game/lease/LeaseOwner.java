package org.gms.domain.game.lease;

/**
 * 怪物控制租约归属身份（事故报告阶段 B：按 {@code (characterId, sessionId, generation)}
 * 三元组证明归属，不能靠对象引用相等或角色 ID 唯一性猜测）。
 *
 * <p>不可变值对象（红线 11 用 record）。sessionId = 连接级不可变会话 id；
 * generation = 角色被某连接认领时的单调代际。
 */
public record LeaseOwner(long characterId, long sessionId, long generation) {
}
