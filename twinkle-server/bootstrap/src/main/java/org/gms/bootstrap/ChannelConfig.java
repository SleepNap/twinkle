package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.channel.admin.ChannelAdminService;
import org.gms.channel.admin.ChannelEventPublisher;
import org.gms.channel.AttackHandler;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.MonsterSpawnService;
import org.gms.channel.MovePlayerHandler;
import org.gms.channel.NpcTalkHandler;
import org.gms.channel.NpcTalkMoreHandler;
import org.gms.channel.PlayerInteractionHandler;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.UseItemHandler;
import org.gms.data.repo.CharacterRepository;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.script.ScriptManager;
import org.gms.event.EventBus;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.service.admin.AdminService;
import org.gms.wz.MapLoader;

import java.nio.file.Path;
import java.util.Map;

/**
 * 频道服装配（架构 M2 进图 + M3-5 游戏内协议 handler）。
 *
 * <p>角色加载（CharacterLoader）、地图缓存（ChannelMapManager，WZ 路径来自
 * {@code twinkle.wz.path}）、在线表（PlayerStorage）、会话注册表（PlayerSessionRegistry）、
 * 刷怪服务（MonsterSpawnService）、全部游戏内 handler + 可替换层 system 在此装配。
 * VersionGate 全局单例（热重载换代判定，M0 定稿）也在此统一装配。
 */
@Factory
public class ChannelConfig {

    @Bean
    @Singleton
    public VersionGate versionGate() {
        return new DefaultVersionGate();
    }

    @Bean
    @Singleton
    public EntityReloadCoordinator entityReloadCoordinator() {
        return new EntityReloadCoordinator();
    }

    @Bean
    @Singleton
    public EntityReloadService entityReloadService(EntityReloadCoordinator coordinator, VersionGate versionGate) {
        return new EntityReloadService(coordinator, versionGate);
    }

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
    public MonsterSpawnService monsterSpawnService(Map<Integer, MobData> mobData, PlayerSessionRegistry sessions) {
        return new MonsterSpawnService(mobData, sessions);
    }

    @Bean
    @Singleton
    public ChannelServer channelServer(HandlerRegistry registry, PlayerSessionRegistry playerSessionRegistry,
                                       ChannelEventPublisher eventPublisher, PlayerStorage playerStorage,
                                       org.gms.channel.persist.CharacterSaveQueue saveQueue) {
        return new ChannelServer(registry, session -> {
            // 断链注销：在线事件 + 会话注册表 + 在线表 + 地图 + 存档（IO 线程快速返回，重活入队）
            org.gms.domain.game.Character chr = session.getAttr("character");
            if (chr != null) {
                eventPublisher.playerOffline(chr.getId());
                playerSessionRegistry.unregister(chr.getId());
                playerStorage.remove(chr);
                if (chr.getMapObject() != null) {
                    chr.getMapObject().removeCharacter(chr);
                }
                saveQueue.save(chr); // 下线存档（L4 增量 FLUSH 队列，单写执行器）
            }
            org.gms.channel.NpcTalkHandler.closeConversation(session);
        });
    }

    @Bean
    @Singleton
    public ChannelEventPublisher channelEventPublisher(EventBus eventBus) {
        return new ChannelEventPublisher(eventBus);
    }

    @Bean
    @Singleton
    public AdminService adminService(PlayerStorage playerStorage, PlayerSessionRegistry playerSessionRegistry,
                                     @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId) {
        return new ChannelAdminService(playerStorage, playerSessionRegistry, channelId);
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
                                                           @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId) {
        return new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(characterRepository, characterLoader, channelMapManager, playerStorage, playerSessionRegistry, monsterSpawnService, channelId, eventPublisher),
                new PlayerMapTransitionHandler(),
                new MovePlayerHandler(movementSystem, playerSessionRegistry),
                new AttackHandler(combatSystem, playerSessionRegistry, false, false),
                new AttackHandler(combatSystem, playerSessionRegistry, true, false),
                new AttackHandler(combatSystem, playerSessionRegistry, false, true),
                new PlayerInteractionHandler(tradeSystem, playerSessionRegistry, entityReloadCoordinator),
                new NpcTalkHandler(scriptManager, itemSystem, questSystem),
                new NpcTalkMoreHandler(),
                new UseItemHandler(itemSystem, itemData));
    }
}
