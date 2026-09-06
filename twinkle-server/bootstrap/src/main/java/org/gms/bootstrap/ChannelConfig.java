package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.channel.ChannelPlayerDirectory;
import org.gms.channel.PlayerCharacterAssembler;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.domain.script.ScriptManager;
import org.gms.event.EventBus;
import org.gms.event.ReliableEventBus;
import org.gms.event.ReliableReceiver;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.RestartCoordinator;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.netty.HeartbeatConfig;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.role.ChannelProcessCondition;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.IntercoordService;
import org.gms.tick.TickScheduler;
import org.gms.wz.WzReloadCoordinator;
import org.gms.wz.WzResourceRegistry;
import org.gms.concurrent.ThreadManager;

/** Channel worker 装配：共享资源只建一次，频道运行态由 {@link ChannelWorker} 按清单创建。 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class ChannelConfig {

    @Bean
    @Singleton
    public PlayerCharacterAssembler characterLoader(VersionGate versionGate,
                                           org.gms.persistence.repo.InventoryItemRepository inventoryItemRepository,
                                           org.gms.persistence.repo.QuestRepository questRepository,
                                           org.gms.persistence.repo.SkillRepository skillRepository) {
        return new PlayerCharacterAssembler(versionGate, inventoryItemRepository, questRepository, skillRepository);
    }

    @Bean
    @Singleton
    public ChannelPlayerDirectory channelPlayerDirectory() {
        return new ChannelPlayerDirectory();
    }

    @Bean(preDestroy = "close")
    @Singleton
    public ChannelWorker channelWorker(
            ChannelWorkerSpec spec,
            PlayerCharacterRepository characterRepository,
            PlayerCharacterAssembler characterLoader,
            WzResourceRegistry wzResources,
            GameDataProvider gameData,
            ScriptManager scriptManager,
            MovementSystem movementSystem,
            CombatSystem combatSystem,
            TradeSystem tradeSystem,
            ItemSystem itemSystem,
            QuestSystem questSystem,
            EntityReloadCoordinator entityReloadCoordinator,
            EventBus eventBus,
            ReliableEventBus reliableEventBus,
            ReliableReceiver reliableReceiver,
            IntercoordService intercoord,
            BuddyListRepository buddyListRepository,
            CharacterSaveQueue saveQueue,
            RestartService restartService,
            RestartCoordinator restartCoordinator,
            ChannelPlayerDirectory playerDirectory,
            TickScheduler tickScheduler,
            HeartbeatConfig heartbeatConfig,
            org.gms.channel.NpcShopCatalog shopCatalog,
            org.gms.replaceable.ProgressionSystem progressionSystem,
            VersionGate versionGate,
            ThreadManager background,
            @Property(name = "twinkle.net.world.id", defaultValue = "0") int worldId,
            @Property(name = "twinkle.lease.ttlSeconds", defaultValue = "50") long leaseTtlSeconds,
            @Property(name = "twinkle.lease.cooldownSeconds", defaultValue = "15") long leaseCooldownSeconds,
            @Property(name = "twinkle.lease.sweepIntervalMs", defaultValue = "10000") long leaseSweepIntervalMillis,
            @Property(name = "twinkle.admin.restart.exit", defaultValue = "true") boolean exitOnRestart) {
        ChannelRuntimeFactory runtimeFactory = new ChannelRuntimeFactory(characterRepository,
                characterLoader, wzResources, gameData, scriptManager, movementSystem, combatSystem,
                tradeSystem, itemSystem, questSystem, entityReloadCoordinator, eventBus,
                reliableEventBus, reliableReceiver, intercoord, buddyListRepository, saveQueue,
                playerDirectory, tickScheduler, heartbeatConfig,
                org.gms.net.packet.v83.V83WorldId.validate(worldId), leaseTtlSeconds,
                leaseCooldownSeconds, leaseSweepIntervalMillis, shopCatalog, progressionSystem, versionGate, background);
        return new ChannelWorker(spec, runtimeFactory, wzResources, scriptManager, restartService,
                restartCoordinator, intercoord, playerDirectory, tickScheduler, exitOnRestart);
    }

    @Bean
    @Singleton
    public WzReloadCoordinator wzReloadCoordinator(ChannelWorker worker) {
        return worker.wzReloadCoordinator();
    }

    @Bean
    @Singleton
    public AdminService adminService(ChannelWorker worker) {
        return new WorkerAdminService(worker);
    }
}
