package org.gms.data.config;

import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.config.ConfigChangeEvent;
import org.gms.config.ConfigFacade;
import org.gms.data.entity.ParamConf;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DB 实现：{@code param_conf} 表 + EventBus 广播（架构 5.2 L1）。
 *
 * <p>启动时一次性加载全部配置到内存索引（O(1) 读）。变更链路：写 DB → version +1 → 广播
 * {@link ConfigChangeEvent}（订阅者重读）。
 *
 * <h2>为什么启动时全量加载</h2>
 * <ul>
 *   <li>配置文件数量有限（百级），启动开销毫秒级。</li>
 *   <li>运行期读 0 IO，是热路径性能保障。</li>
 *   <li>变更走广播，订阅者按需重读，避免"每次 get 都查 DB"的反模式。</li>
 * </ul>
 *
 * <h2>依赖关系</h2>
 * <p>依赖 {@link ParamConfRepository}（接口）—— 数据库落地方式（MyBatis-Flex / JDBC / 内存 mock）
 * 不影响 facade 本身的逻辑。架构 1.3：内存态是权威，DB 只是持久化 + 查询层。
 */
@Singleton
@Log4j2
public final class DbConfigFacade implements ConfigFacade {



    /** 广播到全订阅者的逻辑目标。配置中心是基础设施，不是单频道消息。 */
    private static final String BROADCAST_TARGET = "config-center";

    private final ParamConfRepository repository;
    private final EventBus eventBus;

    /**
     * 内存索引：key → 值。订阅者按需重读时直接拿此索引。
     */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 当前版本号。{@code 0} = 初始（启动时）。每次外部写入后 +1，{@link #signalChange()} 触发。
     */
    private final AtomicLong version = new AtomicLong(ConfigChangeEvent.VERSION_INITIAL);

    public DbConfigFacade(ParamConfRepository repository, EventBus eventBus) {
        this.repository = repository;
        this.eventBus = eventBus;
        loadAll();
        // 订阅自己广播的事件，刷新内部缓存。其他订阅者用类型 / target 自取。
        eventBus.subscribe(BROADCAST_TARGET, ConfigChangeEvent.class, event -> {
            if (event.isInitial()) {
                return;
            }
            log.info(I18n.message("log.config.change_received"), event.version());
            loadAll();
        });
    }

    private void loadAll() {
        var all = repository.selectAll();
        ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
        for (ParamConf p : all) {
            fresh.put(p.getConfigKey(), p.getConfigValue());
        }
        cache.clear();
        cache.putAll(fresh);
        log.info(I18n.message("log.config.loaded"), cache.size());
    }

    @Override
    public long currentVersion() {
        return version.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        String raw = cache.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            if (type == String.class) {
                return (Optional<T>) Optional.of(raw);
            }
            if (type == Long.class) {
                return (Optional<T>) Optional.of(Long.parseLong(raw.trim()));
            }
            if (type == Integer.class) {
                return (Optional<T>) Optional.of(Integer.parseInt(raw.trim()));
            }
            if (type == Boolean.class) {
                // SQLite/PG/MySQL 字面量统一：true/false、1/0 都接受
                String v = raw.trim().toLowerCase();
                boolean t = v.equals("true") || v.equals("1") || v.equals("yes");
                return (Optional<T>) Optional.of(t);
            }
            if (type == Double.class) {
                return (Optional<T>) Optional.of(Double.parseDouble(raw.trim()));
            }
            return (Optional<T>) Optional.of(raw);
        } catch (NumberFormatException e) {
            log.warn(I18n.message("log.config.convert_failed"), key, raw, type.getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void signalChange() {
        long next = version.incrementAndGet();
        eventBus.send(BROADCAST_TARGET, new ConfigChangeEvent(next, Instant.now()));
    }

    /**
     * 写入并触发版本广播（供管理面板 / 调试用）。业务代码不直接调用——通过 DatabaseHealthAPI
     * 或 admin 控制台调用（架构 4.6.5：DB 真值 + admin UI 改）。
     */
    public void upsert(String key, String value) {
        long now = version.incrementAndGet();
        var existing = repository.selectByKey(key);
        if (existing.isEmpty()) {
            ParamConf p = new ParamConf(key, value);
            p.setVersion(now);
            p.setUpdatedAt(Instant.now().toString());
            repository.insert(p);
        } else {
            ParamConf p = existing.get();
            p.setConfigValue(value);
            p.setVersion(now);
            p.setUpdatedAt(Instant.now().toString());
            repository.update(p);
        }
        eventBus.send(BROADCAST_TARGET, new ConfigChangeEvent(now, Instant.now()));
        cache.put(key, value);
    }

    /** 调试：当前所有配置项。 */
    public java.util.Map<String, String> snapshot() {
        return java.util.Map.copyOf(cache);
    }
}
