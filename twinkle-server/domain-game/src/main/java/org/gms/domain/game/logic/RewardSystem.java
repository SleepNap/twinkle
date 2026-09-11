package org.gms.domain.game.logic;

import org.gms.domain.game.spi.RewardState;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;

/** RewardSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface RewardSystem {
    public RewardResult.Status grant(RewardState state, RewardGrant grant);
}
