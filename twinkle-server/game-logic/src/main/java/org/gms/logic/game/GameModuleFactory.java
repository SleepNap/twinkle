package org.gms.logic.game;

import java.util.Map;
import org.gms.domain.game.logic.*;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.module.BusinessModule;
import org.gms.module.BusinessModuleFactory;
import org.gms.module.HostServices;

/** 一次创建完整游戏逻辑组；不持有角色状态，不注册线程或全局回调。 */
public final class GameModuleFactory implements BusinessModuleFactory {
    public GameModuleFactory() { }
    @Override public BusinessModule create(HostServices host) {
        var gate = host.require(VersionGate.class);
        var data = host.require(GameDataProvider.class);
        var items = new DefaultItemSystem(gate, data);
        var progression = new DefaultProgressionSystem(gate);
        Map<Class<?>, Object> services = Map.ofEntries(
                Map.entry(AvatarSystem.class, new DefaultAvatarSystem(progression::accepts)),
                Map.entry(CombatSystem.class, new DefaultCombatSystem(gate)),
                Map.entry(ControlsSystem.class, new DefaultControlsSystem(progression::accepts, data)),
                Map.entry(EquipmentSystem.class, new DefaultEquipmentSystem(progression::accepts, data)),
                Map.entry(HealthRecoverySystem.class, new DefaultHealthRecoverySystem(gate)),
                Map.entry(ItemSystem.class, items),
                Map.entry(MovementSystem.class, new DefaultMovementSystem(gate)),
                Map.entry(PartySystem.class, new DefaultPartySystem()),
                Map.entry(ProgressionSystem.class, progression),
                Map.entry(QuestSystem.class, new DefaultQuestSystem(gate)),
                Map.entry(RewardSystem.class, new DefaultRewardSystem(gate, data)),
                Map.entry(TradeSystem.class, new DefaultTradeSystem(gate, items)));
        return () -> services;
    }
}
