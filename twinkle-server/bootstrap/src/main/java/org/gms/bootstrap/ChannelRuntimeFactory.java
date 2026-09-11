package org.gms.bootstrap;

import org.gms.channel.*;
import org.gms.channel.admin.ChannelEventPublisher;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.domain.game.lease.DefaultControllerLeaseService;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.domain.script.ScriptManager;
import org.gms.event.EventBus;
import org.gms.event.ReliableEventBus;
import org.gms.event.ReliableReceiver;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.net.netty.HeartbeatConfig;
import org.gms.net.packet.HandlerRegistry;
import org.gms.domain.game.logic.*;
import org.gms.service.intercoord.IntercoordService;
import org.gms.tick.TickScheduler;
import org.gms.wz.WzResourceRegistry;
import org.gms.concurrent.GameExecution;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.concurrent.ThreadManager;

/** 构造一个频道的全部私有运行态；失败时回滚已经注册的目录和 tick 资源。 */
public final class ChannelRuntimeFactory {

    private final PlayerCharacterRepository characterRepository;
    private final PlayerCharacterAssembler characterLoader;
    private final WzResourceRegistry wzResources;
    private final GameDataProvider gameData;
    private final ScriptManager scriptManager;
    private final MovementSystem movementSystem;
    private final CombatSystem combatSystem;
    private final TradeSystem tradeSystem;
    private final ItemSystem itemSystem;
    private final QuestSystem questSystem;
    private final EntityReloadCoordinator entityReloadCoordinator;
    private final EventBus eventBus;
    private final ReliableEventBus reliableEventBus;
    private final ReliableReceiver reliableReceiver;
    private final IntercoordService intercoord;
    private final BuddyListRepository buddyListRepository;
    private final CharacterSaveQueue saveQueue;
    private final ChannelPlayerDirectory playerDirectory;
    private final TickScheduler tickScheduler;
    private final HeartbeatConfig heartbeatConfig;
    private final int worldId;
    private final long leaseTtlSeconds;
    private final long leaseCooldownSeconds;
    private final long leaseSweepIntervalMillis;
    private final NpcShopCatalog shopCatalog;
    private final ProgressionSystem progressionSystem;
    private final VersionGate versionGate;
    private final ThreadManager background;
    private final GameLogicRuntime logic;

    public ChannelRuntimeFactory(PlayerCharacterRepository characterRepository, PlayerCharacterAssembler characterLoader,
                                 WzResourceRegistry wzResources, GameDataProvider gameData,
                                 ScriptManager scriptManager, MovementSystem movementSystem,
                                 CombatSystem combatSystem, TradeSystem tradeSystem, ItemSystem itemSystem,
                                 QuestSystem questSystem, EntityReloadCoordinator entityReloadCoordinator,
                                 EventBus eventBus, ReliableEventBus reliableEventBus,
                                 ReliableReceiver reliableReceiver, IntercoordService intercoord,
                                 BuddyListRepository buddyListRepository, CharacterSaveQueue saveQueue,
                                 ChannelPlayerDirectory playerDirectory, TickScheduler tickScheduler,
                                 HeartbeatConfig heartbeatConfig, int worldId, long leaseTtlSeconds,
                                 long leaseCooldownSeconds, long leaseSweepIntervalMillis, NpcShopCatalog shopCatalog,
                                 ProgressionSystem progressionSystem, VersionGate versionGate, ThreadManager background, GameLogicRuntime logic) {
        this.characterRepository = characterRepository;
        this.characterLoader = characterLoader;
        this.wzResources = wzResources;
        this.gameData = gameData;
        this.scriptManager = scriptManager;
        this.movementSystem = movementSystem;
        this.combatSystem = combatSystem;
        this.tradeSystem = tradeSystem;
        this.itemSystem = itemSystem;
        this.questSystem = questSystem;
        this.entityReloadCoordinator = entityReloadCoordinator;
        this.eventBus = eventBus;
        this.reliableEventBus = reliableEventBus;
        this.reliableReceiver = reliableReceiver;
        this.intercoord = intercoord;
        this.buddyListRepository = buddyListRepository;
        this.saveQueue = saveQueue;
        this.playerDirectory = playerDirectory;
        this.tickScheduler = tickScheduler;
        this.heartbeatConfig = heartbeatConfig;
        this.worldId = worldId;
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.leaseCooldownSeconds = leaseCooldownSeconds;
        this.leaseSweepIntervalMillis = leaseSweepIntervalMillis;
        this.shopCatalog = shopCatalog;
        this.progressionSystem = progressionSystem;
        this.versionGate = versionGate;
        this.background = background;
        this.logic = logic;
    }

    public ChannelRuntime create(ChannelWorkerSpec.Endpoint endpoint) {
        int channelId = endpoint.channelId();
        GameExecution execution = new GameExecution("channel-state-" + channelId, versionGate);
        ChannelTickScheduler channelTicks = new ChannelTickScheduler(tickScheduler, execution);
        HandlerRegistry handlers = new HandlerRegistry(execution);
        ChannelMapManager maps = new ChannelMapManager(wzResources, channelId, execution);
        PlayerStorage players = new PlayerStorage(execution);
        PlayerSessionRegistry sessions = new PlayerSessionRegistry(execution);
        playerDirectory.register(channelId, players);

        DefaultControllerLeaseService leases = null;
        MonsterSpawnService monsters = null;
        ChannelMessageSubscriber messages = null;
        ChannelChangeReceiver changes = null;
        ChannelLocationBinder locations = null;
        MonsterReassignTickHandler reassign = null;
        ChannelGameplay gameplay = null;
        try {
            logic.bind(execution, () -> {
                for (var player : players.all()) {
                    if (!entityReloadCoordinator.isSafe(player.getId())
                            && !entityReloadCoordinator.interrupt(player.getId())) {
                        throw new IllegalStateException("频道仍有未完成操作：" + player.getId());
                    }
                }
            });
            leases = new DefaultControllerLeaseService(leaseTtlSeconds, leaseCooldownSeconds,
                    leaseSweepIntervalMillis, tickScheduler.intervalMillis());
            monsters = new MonsterSpawnService(gameData, sessions, leases);
            ChannelEventPublisher publisher = new ChannelEventPublisher(eventBus, channelId);
            DefaultControllerLeaseService runtimeLeases = leases;
            ChannelServer server = new ChannelServer(handlers, session -> {
                org.gms.domain.game.PlayerCharacter character = session.getAttr("character");
                if (character == null) {
                    NpcTalkHandler.closeConversation(session);
                    return;
                }
                Runnable cancelTrade = session.getAttr("cancelTrade");
                if (cancelTrade != null) cancelTrade.run();
                if (!sessions.unregister(character.getId(), session)) return;
                publisher.playerOffline(character.getId());
                players.remove(character);
                if (character.getMapObject() != null) character.getMapObject().removeCharacter(character);
                Long generation = session.getAttr("sessionGeneration");
                if (generation != null) {
                    runtimeLeases.onDisconnect(character.getId(), session.sessionId(), generation);
                }
                saveQueue.save(character);
                NpcTalkHandler.closeConversation(session);
            }, heartbeatConfig);

            new ChannelHandlerRegistrar(
                    new PlayerLoggedinHandler(characterRepository, characterLoader, maps, players, sessions,
                            monsters, channelId, publisher, leases, background, saveQueue),
                    new PlayerMapTransitionHandler(), new MovePlayerHandler(movementSystem, sessions),
                    new AttackHandler(combatSystem, sessions, leases, false, false),
                    new AttackHandler(combatSystem, sessions, leases, true, false),
                    new AttackHandler(combatSystem, sessions, leases, false, true),
                    new PlayerInteractionHandler(tradeSystem, sessions, entityReloadCoordinator),
                    new NpcTalkHandler(scriptManager, itemSystem, questSystem), new NpcTalkMoreHandler(),
                    new UseItemHandler(itemSystem, gameData),
                    new WhisperHandler(channelId, intercoord, eventBus, sessions),
                    new ChangeChannelHandler(channelId, intercoord, reliableEventBus, sessions, players, saveQueue, background),
                    new BuddyHandler(channelId, intercoord, eventBus, sessions, buddyListRepository),
                    new MoveLifeHandler(leases, sessions), new GeneralChatHandler(sessions)).register(handlers);

            messages = new ChannelMessageSubscriber(channelId, intercoord, sessions, eventBus);
            changes = new ChannelChangeReceiver(channelId, reliableReceiver, eventBus);
            locations = new ChannelLocationBinder(worldId, channelId, intercoord, eventBus);
            new ChannelActivityService(worldId, channelId, players, sessions, saveQueue, publisher);
            reassign = new MonsterReassignTickHandler(maps, monsters, tickScheduler.ticksFor(10_000L));
            channelTicks.register(leases);
            channelTicks.register(reassign);
            gameplay = new ChannelGameplay(handlers, maps, monsters, leases, channelId, gameData,
                    sessions, itemSystem, questSystem, scriptManager, shopCatalog, channelTicks,
                    wzResources, progressionSystem, logic.service(AvatarSystem.class), logic.service(ControlsSystem.class),
                    logic.service(EquipmentSystem.class), logic.service(PartySystem.class));
            ChannelRuntime runtime = new ChannelRuntime(endpoint, handlers, maps, players, sessions, leases, monsters,
                    reassign, server, channelTicks, messages, changes, locations, gameplay, execution);
            runtime.rewards(new RewardDeliveryService(players, sessions, logic.service(RewardSystem.class), saveQueue));
            return runtime;
        } catch (RuntimeException error) {
            closeQuietly(gameplay);
            closeQuietly(locations);
            closeQuietly(changes);
            closeQuietly(messages);
            channelTicks.close();
            execution.close();
            if (monsters != null) monsters.close();
            playerDirectory.unregister(channelId, players);
            throw error;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 原始构造异常优先向上传播。
        }
    }
}
