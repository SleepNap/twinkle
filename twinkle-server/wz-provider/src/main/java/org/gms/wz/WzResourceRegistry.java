package org.gms.wz;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.i18n.I18n;



/**
 * WZ 资源注册中心。
 *
 * <p>重载时先在旧快照之外构建全部资源；全部成功后仅执行一次引用替换。解析过程中业务线程持续
 * 使用旧版本，失败不会产生半新半旧状态。快照内可包含预加载投影或按需 catalog。
 */
public final class WzResourceRegistry implements GameDataProvider {

    public record ReloadReport(long version, Map<String, Integer> resources) {
        public ReloadReport {
            resources = Map.copyOf(resources);
        }
    }

    private record Snapshot(long version, Map<WzResourceKey<?>, Object> resources,
                            Map<String, Integer> counts, String digest) {
    }

    /** 已完整构建、尚未发布的新快照。 */
    public static final class PreparedReload {
        private final WzResourceRegistry origin;
        private final long baseVersion;
        private final Snapshot replacement;

        private PreparedReload(WzResourceRegistry origin, long baseVersion, Snapshot replacement) {
            this.origin = origin;
            this.baseVersion = baseVersion;
            this.replacement = replacement;
        }

        public String digest() { return replacement.digest(); }
        public long version() { return replacement.version(); }

        @SuppressWarnings("unchecked")
        public <T> T resource(WzResourceKey<T> key) {
            Object value = replacement.resources().get(key);
            if (value == null) {
                throw new IllegalArgumentException("WZ resource is not registered: " + key.name());
            }
            return (T) value;
        }
    }

    private final Path wzRoot;
    private final List<WzResourceLoader<?>> loaders;
    private final Executor executor;
    private final AtomicReference<Snapshot> current;
    private final ThreadLocal<Snapshot> operationSnapshot = new ThreadLocal<>();

    /** 一个频道已提交的资源代际，与该频道的地图/怪物投影一起推进。 */
    public final class View {
        private volatile Snapshot adopted = current.get();

        private View() { }

        public long version() { return adopted.version(); }
        public String digest() { return adopted.digest(); }

        public <T> T resource(WzResourceKey<T> key) { return read(adopted, key); }

        public void run(Runnable operation) {
            Snapshot previous = operationSnapshot.get();
            operationSnapshot.set(adopted);
            try { operation.run(); }
            finally {
                if (previous == null) operationSnapshot.remove();
                else operationSnapshot.set(previous);
            }
        }

        /** 先验证再改运行态；允许落后频道跳过未成功提交的中间代。 */
        public void validate(PreparedReload prepared) {
            if (prepared.origin != WzResourceRegistry.this || prepared.replacement.version() <= adopted.version())
                throw new IllegalStateException(I18n.message("error.wz.stale_channel_snapshot"));
        }

        public void publish(PreparedReload prepared) {
            validate(prepared);
            adopted = prepared.replacement;
        }
    }

    public View view() { return new View(); }

    public WzResourceRegistry(Path wzRoot, List<WzResourceLoader<?>> loaders, Executor executor) {
        this.wzRoot = Objects.requireNonNull(wzRoot, "wzRoot").toAbsolutePath().normalize();
        this.loaders = validate(loaders);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.current = new AtomicReference<>(buildSnapshot(1));
    }

    /** 全量准备成功后原子换代；任一 loader 抛错时当前快照保持不变。 */
    public synchronized ReloadReport reload() {
        return commit(prepareReload());
    }

    /** 构建候选快照但不影响当前读流量，供地图等运行态资源先完成校验。 */
    public synchronized PreparedReload prepareReload() {
        long baseVersion = current.get().version();
        return new PreparedReload(this, baseVersion, buildSnapshot(baseVersion + 1));
    }

    /** 发布已准备快照；准备后若已有其他换代则拒绝覆盖。 */
    public synchronized ReloadReport commit(PreparedReload prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.origin != this || current.get().version() != prepared.baseVersion) {
            throw new IllegalStateException("WZ snapshot changed while reload was being prepared");
        }
        current.set(prepared.replacement);
        return new ReloadReport(prepared.replacement.version(), prepared.replacement.counts());
    }

    public <T> T resource(WzResourceKey<T> key) {
        return read(readSnapshot(), key);
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(Snapshot snapshot, WzResourceKey<T> key) {
        Object value = snapshot.resources().get(key);
        if (value == null) {
            throw new IllegalArgumentException("WZ resource is not registered: " + key.name());
        }
        return (T) value;
    }

    private Snapshot readSnapshot() {
        Snapshot pinned = operationSnapshot.get();
        return pinned == null ? current.get() : pinned;
    }

    public Path root() {
        return wzRoot;
    }

    @Override
    public ItemData item(int itemId) {
        return resource(WzResources.ITEMS).get(itemId);
    }

    @Override
    public MobData mob(int mobId) {
        return resource(WzResources.MOBS).get(mobId);
    }

    @Override
    public long version() {
        return readSnapshot().version();
    }

    public ReloadReport status() {
        Snapshot snapshot = current.get();
        return new ReloadReport(snapshot.version(), snapshot.counts());
    }

    private Snapshot buildSnapshot(long version) {
        WzSourceSnapshot source = WzSourceSnapshot.freeze(wzRoot);
        Map<WzResourceKey<?>, Object> resources = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<CompletableFuture<LoadedResource>> futures = loaders.stream()
                .map(loader -> CompletableFuture.supplyAsync(
                        () -> loadOne(loader, source.root()), executor))
                .toList();
        try {
            for (CompletableFuture<LoadedResource> future : futures) {
                LoadedResource loaded = future.join();
                resources.put(loaded.key(), loaded.value());
                counts.put(loaded.key().name(), loaded.entryCount());
            }
        } catch (CompletionException e) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
        return new Snapshot(version, Map.copyOf(resources), Map.copyOf(counts), source.digest());
    }

    private static <T> LoadedResource loadOne(WzResourceLoader<T> loader, Path wzRoot) {
        T value = Objects.requireNonNull(loader.load(wzRoot),
                () -> "WZ loader returned null: " + loader.key().name());
        return new LoadedResource(loader.key(), value, loader.entryCount(value));
    }

    private record LoadedResource(WzResourceKey<?> key, Object value, int entryCount) {
    }

    private List<WzResourceLoader<?>> validate(List<WzResourceLoader<?>> candidates) {
        List<WzResourceLoader<?>> result = new ArrayList<>(Objects.requireNonNull(candidates, "loaders"));
        result.sort(Comparator.comparing(loader -> loader.key().name()));
        Map<String, WzResourceLoader<?>> unique = new LinkedHashMap<>();
        for (WzResourceLoader<?> loader : result) {
            WzResourceLoader<?> previous = unique.putIfAbsent(loader.key().name(), loader);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate WZ resource: " + loader.key().name());
            }
        }
        return List.copyOf(result);
    }
}
