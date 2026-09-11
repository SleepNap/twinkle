package org.gms.domain.game.logic;

import org.gms.domain.game.spi.CharacterState;

/** HealthRecoverySystem 的稳定调用契约；实现由逻辑包提供。 */
public interface HealthRecoverySystem {
    public boolean tick(CharacterState state);
}
