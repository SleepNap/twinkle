package org.gms.domain.game.spi;

import java.util.List;
import java.util.Map;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;

/** 稳定状态提交端口：奖励资产与幂等回执在同一次状态提交和存档中更新。 */
public interface RewardState extends CharacterState {
    public RewardResult.Status applyReward(RewardGrant grant, List<TradeItemSnapshot> items,
                                           Map<Integer, Integer> slotLimits);
}
