package org.gms.bootstrap;

import java.util.List;
import java.util.Map;
import org.gms.concurrent.GameExecution;
import org.gms.domain.game.logic.*;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.module.HostServices;
import org.gms.module.ModuleRuntime;
import org.gms.module.ModuleRegistry;

/** 游戏业务 JAR 的宿主；角色、会话、地图和执行队列始终保留在稳定层。 */
public final class GameLogicRuntime implements AutoCloseable {
    public static final List<Class<?>> CONTRACTS = List.of(AvatarSystem.class, CombatSystem.class,
            ControlsSystem.class, EquipmentSystem.class, HealthRecoverySystem.class, ItemSystem.class,
            MovementSystem.class, PartySystem.class, ProgressionSystem.class, QuestSystem.class,
            RewardSystem.class, TradeSystem.class);
    private final ModuleRuntime runtime;
    private final ModuleRegistry registry;

    public GameLogicRuntime(ModuleRegistry registry, GameDataProvider data) throws Exception {
        this.registry = registry;
        runtime = registry.register("game-logic", "org.gms.logic.game.", CONTRACTS,
                new HostServices(Map.of(GameDataProvider.class, data)));
    }

    public <T> T service(Class<T> contract) { return runtime.service(contract); }
    public void bind(GameExecution execution, Runnable safePoint) { runtime.bind(execution, safePoint); }
    public ModuleRuntime.Update reload() throws Exception { return registry.reload("game-logic"); }
    public String digest() { return runtime.digest(); }
    @Override public void close() { runtime.close(); }
}
