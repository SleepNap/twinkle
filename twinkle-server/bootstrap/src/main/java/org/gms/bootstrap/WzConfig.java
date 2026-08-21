package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.concurrent.ThreadManager;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.i18n.I18nBootstrap;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.HealthRecoverySystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.role.ChannelProcessCondition;
import org.gms.wz.WzResourceLoader;
import org.gms.wz.WzResourceRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * WZ 数据 + 可替换层逻辑系统装配（架构 M3-5：协议层接入的前置缺件）。
 *
 * <p>所有 {@link WzResourceLoader} Bean 自动进入统一注册中心。注册中心构建不可变快照并
 * 原子换代，不使用磁盘序列化缓存；新增 WZ 类型无需修改本装配类。
 *
 * <p>WZ 数据 + 可替换层是频道进程专属（split 下 coordinator 管理进程不装配）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class WzConfig {

    @Bean
    @Singleton
    public WzResourceRegistry wzResourceRegistry(
            @Property(name = "twinkle.wz.path", defaultValue = "./wz") String wzPath,
            List<WzResourceLoader<?>> loaders,
            I18nBootstrap i18nBootstrap,
            ThreadManager threadManager) {
        // 显式依赖 i18nBootstrap，保证任何 WZ 解析异常都能使用已安装的国际化服务。
        return new WzResourceRegistry(Path.of(wzPath), loaders, threadManager);
    }

    /* ---------- 可替换层逻辑系统（统一经版本门，写前判定，架构 5.3） ---------- */

    @Bean
    @Singleton
    public ItemSystem itemSystem(VersionGate versionGate, GameDataProvider gameData) {
        return new ItemSystem(versionGate, gameData);
    }

    @Bean
    @Singleton
    public CombatSystem combatSystem(VersionGate versionGate) {
        return new CombatSystem(versionGate);
    }

    @Bean
    @Singleton
    public MovementSystem movementSystem(VersionGate versionGate) {
        return new MovementSystem(versionGate);
    }

    @Bean
    @Singleton
    public TradeSystem tradeSystem(VersionGate versionGate, ItemSystem itemSystem) {
        return new TradeSystem(versionGate, itemSystem);
    }

    @Bean
    @Singleton
    public QuestSystem questSystem(VersionGate versionGate) {
        return new QuestSystem(versionGate);
    }

    @Bean
    @Singleton
    public HealthRecoverySystem healthRecoverySystem(VersionGate versionGate) {
        return new HealthRecoverySystem(versionGate);
    }
}
