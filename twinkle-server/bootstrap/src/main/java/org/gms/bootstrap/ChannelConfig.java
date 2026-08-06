package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.PlayerStorage;
import org.gms.data.repo.CharacterRepository;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.wz.MapLoader;

import java.nio.file.Path;

/**
 * 频道服装配（架构 M2 进图：频道组件 bean + 生命周期）。
 *
 * <p>角色加载（CharacterLoader）、地图缓存（ChannelMapManager，WZ 路径来自
 * {@code twinkle.wz.path}）、在线表（PlayerStorage）、频道服（ChannelServer）
 * 在此提供单例。VersionGate 全局单例（热重载换代判定，M0 定稿）也在此统一装配。
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
    public ChannelServer channelServer(HandlerRegistry registry) {
        return new ChannelServer(registry);
    }

    @Bean
    @Singleton
    public ChannelHandlerRegistrar channelHandlerRegistrar(CharacterRepository characterRepository,
                                                           CharacterLoader characterLoader,
                                                           ChannelMapManager channelMapManager,
                                                           PlayerStorage playerStorage,
                                                           @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId) {
        return new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(characterRepository, characterLoader, channelMapManager, playerStorage, channelId),
                new PlayerMapTransitionHandler());
    }
}
