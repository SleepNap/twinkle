package org.gms.replaceable;

import org.gms.domain.game.spi.AvatarState;
import org.gms.domain.game.spi.CharacterState;

import java.util.function.Predicate;

/** 普通表情与背包椅子的动作规则；版本门由频道装配注入。 */
public final class AvatarSystem {
    private final Predicate<CharacterState> accepts;

    public AvatarSystem(Predicate<CharacterState> accepts) { this.accepts = accepts; }

    public boolean express(AvatarState state, int expression) {
        return accepts.test(state) && state.getHp() > 0 && expression >= 1 && expression <= 7;
    }

    public boolean sit(AvatarState state, int itemId, long now) {
        synchronized (state) {
            if (!accepts.test(state) || state.getHp() <= 0 || itemId / 10000 != 301
                    || !state.ownsUsableItem(itemId, (byte) 3, now)) return false;
            state.setChairItemId(itemId);
            return true;
        }
    }

    public boolean stand(AvatarState state) {
        synchronized (state) {
            if (!accepts.test(state)) return false;
            state.setChairItemId(0);
            return true;
        }
    }
}
