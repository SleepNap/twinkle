package org.gms.hotreload;


import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.log4j.Log4j2;

/**
 * 游戏逻辑系统注册表（贡献点版本化，仿 {@code HandlerRegistry} 范式，红线 13）。
 *
 * <p>职责：可替换层游戏逻辑系统（战斗/掉落/移动等）按 key 注册、可热替换。内置系统
 * （CombatSystem 等）在装配层注册进默认槽位，插件可 {@code replace} 升级实现——替换要求
 * 新版本 &gt; 旧版本（行为随版本单调演化，防回退）。
 *
 * <p>M4 落地注册表 + 内置登记；插件逻辑系统贡献点经此进入。宿主侧取值按 key 解析，
 * 热路径（AttackHandler 等）M4 暂不改直接注入（M5 再评估经注册表解析）。
 *
 * @param <T> 逻辑系统类型（按 key 槽位各自类型一致，运行期由调用方 cast）
 */
@Log4j2
public final class LogicSystemRegistry {



    /** 单条注册项：系统实例 + 版本。 */
    public record Registration(Object system, int version) {
    }

    private final ConcurrentMap<String, Registration> slots = new ConcurrentHashMap<>();

    /**
     * 首次注册（默认版本 1）。已存在同 key 时拒绝（用 {@link #replace} 覆盖）。
     */
    public void register(String key, Object system) {
        register(key, system, 1);
    }

    /**
     * 首次注册（指定版本）。已存在同 key 时拒绝。
     */
    public void register(String key, Object system, int version) {
        Registration put = slots.putIfAbsent(key, new Registration(system, version));
        if (put != null) {
            throw new IllegalStateException("逻辑系统 key 已注册: " + key + "（请用 replace 替换）");
        }
    }

    /**
     * 替换已注册的系统。新版本必须高于旧版本（防回退）。
     */
    public void replace(String key, Object system, int version) {
        slots.compute(key, (k, existing) -> {
            if (existing != null && version <= existing.version()) {
                throw new IllegalStateException("替换版本须高于现版本: key=" + key
                        + ", 现有=" + existing.version() + ", 新=" + version);
            }
            return new Registration(system, version);
        });
        log.info("逻辑系统已替换: key={} 版本={}", key, version);
    }

    /**
     * 卸载已注册的系统（插件 unload）。
     *
     * @return 存在并移除返回 true
     */
    public boolean unregister(String key) {
        return slots.remove(key) != null;
    }

    /**
     * 按 key 取系统实例。未注册返回 empty（宿主可回退默认）。
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(String key) {
        Registration r = slots.get(key);
        return r == null ? Optional.empty() : Optional.of((T) r.system());
    }

    /** 已注册的系统数（观测）。 */
    public int registeredCount() {
        return slots.size();
    }
}
