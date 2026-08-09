package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.channel.admin.ChannelAdminService;
import org.gms.channel.admin.ChannelEventPublisher;
import org.gms.channel.AttackHandler;
import org.gms.channel.BuddyHandler;
import org.gms.channel.ChangeChannelHandler;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelMessageSubscriber;
import org.gms.channel.ChannelLocationBinder;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.MonsterSpawnService;
import org.gms.channel.MoveLifeHandler;
import org.gms.channel.MovePlayerHandler;
import org.gms.channel.NpcTalkHandler;
import org.gms.channel.NpcTalkMoreHandler;
import org.gms.channel.PlayerInteractionHandler;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.UseItemHandler;
import org.gms.channel.WhisperHandler;
import org.gms.data.repo.CharacterRepository;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.script.ScriptManager;
import org.gms.event.EventBus;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.role.ChannelProcessCondition;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.IntercoordService;
import org.gms.wz.MapLoader;

import java.nio.file.Path;
import java.util.Map;

/**
 * 频道服装配（架构 M2 进图 + M3-5 游戏内协议 handler）。
 *
 * <p>角色加载（CharacterLoader）、地图缓存（ChannelMapManager，WZ 路径来自
 * {@code twinkle.wz.path}）、在线表（PlayerStorage）、会话注册表（PlayerSessionRegistry）、
 * 刷怪服务（MonsterSpawnService）、全部游戏内 handler + 可替换层 system 在此装配。
 *
 * <p>装配条件（架构 4.1 进程边界是配置）：single 全内嵌 / split 的 channel 角色装配本类；
 * coordinator 角色（管理进程）不装配（游戏世界只在频道进程）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class ChannelConfig {

    @Bean
    @Singleton
    public CharacterLoader characterLoader(VersionGate versionGate) {
        return new CharacterLoader(versionGate);
    }

    @Bean
    @Singleton
    public ChannelMapManager channelMapManager(@Property(name = "twinkle.wz.path", defaultValue = "./wz") String wzPath) {
        return new ChannelMapManager(new MapLoader(Path.of(wzPath)));
    }

    @Bean
    @Singleton
    public PlayerStorage playerStorage() {
        return new PlayerStorage();
    }

    @Bean
    @Singleton
    public PlayerSessionRegistry playerSessionRegistry() {
        return new PlayerSessionRegistry();
    }

    @Bean
    @Singleton
    public MonsterSpawnService monsterSpawnService(Map<Integer, MobData> mobData, PlayerSessionRegistry sessions,
                                                   org.gms.domain.game.lease.ControllerLeaseService leaseService) {
        return new MonsterSpawnService(mobData, sessions, leaseService);
    }

    @Bean
    @Singleton
    public ChannelServer channelServer(HandlerRegistry registry, PlayerSessionRegistry playerSessionRegistry,
                                       ChannelEventPublisher eventPublisher, PlayerStorage playerStorage,
                                       org.gms.channel.persist.CharacterSaveQueue saveQueue,
                                       org.gms.domain.game.lease.ControllerLeaseService leaseService,
                                       org.gms.net.netty.HeartbeatConfig heartbeatConfig) {
        return new ChannelServer(registry, session -> {
            // 断链注销（事故报告阶段 B：compare-and-remove，迟到旧连接不能误删新会话）。
            // IO 线程快速返回，重活入队。
            org.gms.domain.game.Character chr = session.getAttr("character");
            if (chr == null) {
                org.gms.channel.NpcTalkHandler.closeConversation(session);
                return;
            }
            if (!playerSessionRegistry.unregister(chr.getId(), session)) {
                // 本会话已被新代际替代（旧连接迟到关闭）：只计数不清理、不存档——
                // 防旧态覆盖新会话 DB（事故报告 §5.4）。
                return;
            }
            eventPublisher.playerOffline(chr.getId());
            playerStorage.remove(chr);
            if (chr.getMapObject() != null) {
                chr.getMapObject().removeCharacter(chr);
            }
            Long gen = session.getAttr("sessionGeneration");
            if (gen != null) {
                leaseService.onDisconnect(chr.getId(), session.sessionId(), gen);
            }
            saveQueue.save(chr); // 下线存档（L4 增量 FLUSH 队列，单写执行器）
            org.gms.channel.NpcTalkHandler.closeConversation(session);
        }, heartbeatConfig);
    }

    @Bean
    @Singleton
    public ChannelEventPublisher channelEventPublisher(EventBus eventBus) {
        return new ChannelEventPublisher(eventBus);
    }

    @Bean
    @Singleton
    public AdminService adminService(PlayerStorage playerStorage, PlayerSessionRegistry playerSessionRegistry,
                                     ScriptManager scriptManager,
                                     org.gms.channel.persist.RestartService restartService,
                                     org.gms.hotreload.RestartCoordinator restartCoordinator,
                                     @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
                                     @Property(name = "twinkle.admin.restart.exit", defaultValue = "true") boolean exitOnRestart) {
        // 重启编排完成后是否真正退出进程（L4 兜底）。生产默认 true（编排完 System.exit，由外部启动脚本拉起）；
        // 测试/开发置 false（只编排不退出，防杀测试 JVM）——进程边界是配置（铁律 1）。
        Runnable restartProcess = exitOnRestart ? () -> System.exit(0) : () -> { };
        return new ChannelAdminService(playerStorage, playerSessionRegistry, channelId,
                scriptManager, restartService, restartCoordinator, restartProcess);
    }
    @Bean
    @Singleton
    public ChannelHandlerRegistrar channelHandlerRegistrar(CharacterRepository characterRepository,
                                                           CharacterLoader characterLoader,
                                                           ChannelMapManager channelMapManager,
                                                           PlayerStorage playerStorage,
                                                           PlayerSessionRegistry playerSessionRegistry,
                                                           MonsterSpawnService monsterSpawnService,
                                                           MovementSystem movementSystem,
                                                           CombatSystem combatSystem,
                                                           TradeSystem tradeSystem,
                                                           ItemSystem itemSystem,
                                                           QuestSystem questSystem,
                                                           ScriptManager scriptManager,
                                                           ChannelEventPublisher eventPublisher,
                                                           EntityReloadCoordinator entityReloadCoordinator,
                                                           Map<Integer, ItemData> itemData,
                                                           EventBus eventBus,
                                                           org.gms.event.ReliableEventBus reliableEventBus,
                                                           IntercoordService intercoordService,
                                                           org.gms.data.repo.BuddyListRepository buddyListRepository,
                                                           org.gms.domain.game.lease.ControllerLeaseService leaseService,
                                                           @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId) {
        return new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(characterRepository, characterLoader, channelMapManager, playerStorage, playerSessionRegistry, monsterSpawnService, channelId, eventPublisher, leaseService),
                new PlayerMapTransitionHandler(),
                new MovePlayerHandler(movementSystem, playerSessionRegistry),
                new AttackHandler(combatSystem, playerSessionRegistry, leaseService, false, false),
                new AttackHandler(combatSystem, playerSessionRegistry, leaseService, true, false),
                new AttackHandler(combatSystem, playerSessionRegistry, leaseService, false, true),
                new PlayerInteractionHandler(tradeSystem, playerSessionRegistry, entityReloadCoordinator),
                new NpcTalkHandler(scriptManager, itemSystem, questSystem),
                new NpcTalkMoreHandler(),
                new UseItemHandler(itemSystem, itemData),
                new WhisperHandler(channelId, intercoordService, eventBus, playerSessionRegistry),
                new ChangeChannelHandler(channelId, intercoordService, reliableEventBus, playerSessionRegistry),
                new BuddyHandler(channelId, intercoordService, eventBus, playerSessionRegistry, buddyListRepository),
                new MoveLifeHandler(leaseService, playerSessionRegistry));
    }

    /** 频道消息订阅（跨频道悄悄话/公告投递，架构 4.4 消息总线）。 */
    @Bean
    @Singleton
    public ChannelMessageSubscriber channelMessageSubscriber(
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            IntercoordService intercoordService,
            PlayerSessionRegistry playerSessionRegistry,
            EventBus eventBus) {
        return new ChannelMessageSubscriber(channelId, intercoordService, playerSessionRegistry, eventBus);
    }

    /** 玩家定位绑定（进图/下线经事件更新定位表，架构 4.4）。 */
    @Bean
    @Singleton
    public ChannelLocationBinder channelLocationBinder(
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            IntercoordService intercoordService,
            EventBus eventBus) {
        return new ChannelLocationBinder(channelId, intercoordService, eventBus);
    }

    /**
     * 频道启动注册钩子（架构 4.6.4 注册中心：channel 启动向 coordinator 上报）。
     *
     * <p>关键：{@code ChannelRegistry.heartbeat} 用 computeIfPresent，频道必须先 register 才能
     * 心跳续期；此前生产代码无人调 registerChannel，注册表恒空，管理控制台"频道状态"列表拿不到
     * 任何频道。此 @Context 装配在构造期上报本频道（host 读 twinkle.net.channel.host，端口读
     * twinkle.net.channel.port），此后 ChannelLocationBinder 心跳即实时续期。
     */
    @Bean
    @Context
    @Singleton
    public ChannelRegistryRegistrar channelRegistryRegistrar(
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            @Property(name = "twinkle.net.channel.host", defaultValue = "127.0.0.1") String channelHost,
            @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int channelPort,
            IntercoordService intercoordService) {
        return new ChannelRegistryRegistrar(channelId, channelHost, channelPort, intercoordService);
    }

    /** 频道启动注册（@Context 强制装配：构造期上报 → 心跳可续期）。 */
    @Singleton
    static final class ChannelRegistryRegistrar {
        ChannelRegistryRegistrar(int channelId, String host, int port, IntercoordService intercoordService) {
            intercoordService.registerChannel(channelId, host, port, 0);
        }
    }
}
