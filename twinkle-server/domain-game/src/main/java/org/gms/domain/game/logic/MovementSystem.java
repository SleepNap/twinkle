package org.gms.domain.game.logic;

import org.gms.domain.game.spi.MapGeometry;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.AvatarState;

/** MovementSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface MovementSystem {
    public boolean applyMotion(AvatarState state, Integer x, Integer y, Integer stance, Integer foothold);

    public boolean move(CharacterState state, MapGeometry map, int newX, int newY);
}
