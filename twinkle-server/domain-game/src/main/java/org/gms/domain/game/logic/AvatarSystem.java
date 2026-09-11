package org.gms.domain.game.logic;

import org.gms.domain.game.spi.AvatarState;

/** AvatarSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface AvatarSystem {
    public boolean express(AvatarState state, int expression);

    public boolean sit(AvatarState state, int itemId, long now);

    public boolean stand(AvatarState state);
}
