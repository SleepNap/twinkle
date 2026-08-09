package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.HealthRecoverySystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.role.ChannelProcessCondition;
import org.gms.wz.ItemLoader;
import org.gms.wz.MobLoader;
import org.gms.wz.WzCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * WZ 数据 + 可替换层逻辑系统装配（架构 M3-5：协议层接入的前置缺件）。
 *
 * <p>把 Item.wz / Mob.wz 解析结果（经 {@link WzCache} 磁盘缓存，秒级重开）注入
 * {@link ItemSystem} 等可替换层系统，供频道 handler 使用。WZ 目录缺省时（如测试
 * 临时目录只有地图）返回空数据，不阻断启动（架构 6.4 读不到报错的严格校验在
 * 生产装配用真目录兜底）。
 *
 * <p>WZ 数据 + 可替换层是频道进程专属（split 下 coordinator 管理进程不装配）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class WzConfig {

    @Bean
    @Singleton
    public WzCache wzCache(@Property(name = "twinkle.wz.cache-dir", defaultValue = "./data/cache") String cacheDir) {
        return new WzCache(Path.of(cacheDir));
    }

    @Bean
    @Singleton
    public Map<Integer, ItemData> itemData(WzCache wzCache,
                                           @Property(name = "twinkle.wz.path", defaultValue = "./wz") String wzPath) {
        Path root = Path.of(wzPath);
        if (!Files.isDirectory(root.resolve("Item.wz"))) {
            return Map.of();
        }
        ItemLoader loader = new ItemLoader(root);
        return wzCache.items(loader::loadAll);
    }

    @Bean
    @Singleton
    public Map<Integer, MobData> mobData(WzCache wzCache,
                                         @Property(name = "twinkle.wz.path", defaultValue = "./wz") String wzPath) {
        Path root = Path.of(wzPath);
        if (!Files.isDirectory(root.resolve("Mob.wz"))) {
            return Map.of();
        }
        MobLoader loader = new MobLoader(root);
        return wzCache.mobs(loader::loadAll);
    }

    /* ---------- 可替换层逻辑系统（统一经版本门，写前判定，架构 5.3） ---------- */

    @Bean
    @Singleton
    public ItemSystem itemSystem(VersionGate versionGate, Map<Integer, ItemData> itemData) {
        return new ItemSystem(versionGate, itemData);
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
