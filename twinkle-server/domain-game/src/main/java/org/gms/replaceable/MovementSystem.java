package org.gms.replaceable;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

import java.util.OptionalInt;

/**
 * 移动系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>位置更新经 {@link CharacterState} 接口（x/y），落点物理经 {@link MapleMap#groundBelow}
 * （map 包不在规则 3 禁止列表，可替换层可依赖）。v83 移动协议包（PLAYER_MOVE）解析/广播
 * 属网络层，后续接入；本系统管服务端位置状态与落地判定。
 */
public final class MovementSystem {

    private final VersionGate versionGate;

    public MovementSystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /**
     * 角色移动：x 更新为 newX，y 落到 newX 处脚下地面（无地面保持 newY）。
     *
     * @return 版本门拒绝时 false
     */
    public boolean move(CharacterState state, MapleMap map, int newX, int newY) {
        if (versionGate.decide(state) != VersionDecision.ALLOW) {
            return false;
        }
        state.setX(newX);
        OptionalInt ground = map.groundBelow(newX, newY);
        state.setY(ground.isPresent() ? ground.getAsInt() : newY);
        return true;
    }
}
