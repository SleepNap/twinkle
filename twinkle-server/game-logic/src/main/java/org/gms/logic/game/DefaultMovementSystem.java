package org.gms.logic.game;

import org.gms.domain.game.logic.*;

import org.gms.domain.game.spi.MapGeometry;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.AvatarState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

import java.util.OptionalInt;

/**
 * 移动系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>位置更新经 {@link CharacterState} 接口（x/y），落点物理经 {@link MapGeometry#groundBelow}
 * （只读地图几何契约）。v83 移动协议包（PLAYER_MOVE）解析/广播
 * 属网络层，后续接入；本系统管服务端位置状态与落地判定。
 */
public final class DefaultMovementSystem implements MovementSystem {

    private final VersionGate versionGate;

    public DefaultMovementSystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /** 已验证移动流的位置与姿态一次提交；跳跃不提前吸到地面，版本拒绝时不得广播。 */
    public boolean applyMotion(AvatarState state, Integer x, Integer y, Integer stance, Integer foothold) {
        synchronized (state) {
            if (versionGate.decide(state) != VersionDecision.ALLOW || state.getHp() <= 0
                    || state.getChairItemId() != 0) return false;
            if (x != null && y != null) { state.setX(x); state.setY(y); }
            if (stance != null) state.setStance(stance);
            if (foothold != null) state.setFoothold(foothold);
            return true;
        }
    }

    /**
     * 角色移动：x 更新为 newX，y 落到 newX 处脚下地面（无地面保持 newY）。
     *
     * @return 版本门拒绝时 false
     */
    public boolean move(CharacterState state, MapGeometry map, int newX, int newY) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        state.setX(newX);
        OptionalInt ground = map.groundBelow(newX, newY);
        state.setY(ground.isPresent() ? ground.getAsInt() : newY);
        return true;
    }
}
