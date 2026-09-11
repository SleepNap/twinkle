package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.concurrent.ThreadManager;
import org.gms.module.ModuleRegistry;
import org.gms.i18n.I18nBootstrap;
import org.gms.domain.game.logic.*;
import org.gms.domain.game.logic.HealthRecoverySystem;
import org.gms.domain.game.logic.ItemSystem;
import org.gms.domain.game.logic.MovementSystem;
import org.gms.domain.game.logic.QuestSystem;
import org.gms.domain.game.logic.ProgressionSystem;
import org.gms.domain.game.logic.TradeSystem;
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

    @Bean(preDestroy = "close")
    @Singleton
    public GameLogicRuntime gameLogicRuntime(GameDataProvider data, ModuleRegistry registry) throws Exception {
        return new GameLogicRuntime(registry, data);
    }

    @Bean
    @Singleton
    public AvatarSystem avatarSystem(GameLogicRuntime logic) { return logic.service(AvatarSystem.class); }

    @Bean
    @Singleton
    public CombatSystem combatSystem(GameLogicRuntime logic) { return logic.service(CombatSystem.class); }

    @Bean
    @Singleton
    public ControlsSystem controlsSystem(GameLogicRuntime logic) { return logic.service(ControlsSystem.class); }

    @Bean
    @Singleton
    public EquipmentSystem equipmentSystem(GameLogicRuntime logic) { return logic.service(EquipmentSystem.class); }

    @Bean
    @Singleton
    public HealthRecoverySystem healthRecoverySystem(GameLogicRuntime logic) { return logic.service(HealthRecoverySystem.class); }

    @Bean
    @Singleton
    public ItemSystem itemSystem(GameLogicRuntime logic) { return logic.service(ItemSystem.class); }

    @Bean
    @Singleton
    public MovementSystem movementSystem(GameLogicRuntime logic) { return logic.service(MovementSystem.class); }

    @Bean
    @Singleton
    public PartySystem partySystem(GameLogicRuntime logic) { return logic.service(PartySystem.class); }

    @Bean
    @Singleton
    public ProgressionSystem progressionSystem(GameLogicRuntime logic) { return logic.service(ProgressionSystem.class); }

    @Bean
    @Singleton
    public QuestSystem questSystem(GameLogicRuntime logic) { return logic.service(QuestSystem.class); }

    @Bean
    @Singleton
    public RewardSystem rewardSystem(GameLogicRuntime logic) { return logic.service(RewardSystem.class); }

    @Bean
    @Singleton
    public TradeSystem tradeSystem(GameLogicRuntime logic) { return logic.service(TradeSystem.class); }

}
