package org.gms.logic.game;

import org.gms.domain.game.logic.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.gms.domain.game.spi.RewardState;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;

/** 奖励规则：先验证全部模板，再交稳定状态端口一次提交。 */
public final class DefaultRewardSystem implements RewardSystem {
    private final GameDataProvider data;
    private final VersionGate versions;

    public DefaultRewardSystem(VersionGate versions, GameDataProvider data) {
        this.versions = versions;
        this.data = data;
    }

    public RewardResult.Status grant(RewardState state, RewardGrant grant) {
        if (versions.decide(state) != VersionDecision.ALLOW) return RewardResult.Status.BUSY;
        var items = new ArrayList<TradeItemSnapshot>();
        Map<Integer, Integer> limits = new HashMap<>();
        for (var entry : grant.items().entrySet()) {
            var template = data.item(entry.getKey());
            int type = entry.getKey() / 1000000;
            if (template == null || type < 1 || type > 4) return RewardResult.Status.INVALID;
            if (type == 1) {
                if (template.getEquipment() == null || entry.getValue() > 255) return RewardResult.Status.INVALID;
                for (int i = 0; i < entry.getValue(); i++) items.add(DefaultEquipmentSystem.createItem(template));
                limits.put(entry.getKey(), 1);
            } else {
                limits.put(entry.getKey(), Math.max(1, Math.min(32767, template.getSlotMax())));
                items.add(new TradeItemSnapshot((byte) type, (short) 0, entry.getKey(), entry.getValue(),
                        0, 0, null, 0, -1, null, null, null));
            }
        }
        return state.applyReward(grant, items, limits);
    }
}
