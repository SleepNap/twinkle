package org.gms.module;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.gms.i18n.I18n;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.gms.concurrent.GameExecution;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;

/** 稳定代理与业务代际管理；完整操作固定实现，候选全部准备后按执行属主切换。 */
public final class ModuleRuntime implements AutoCloseable {
    public record Update(String module, String digest, Map<String, String> targets) {
        public Update { targets = Map.copyOf(targets); }
    }

    private final String id;
    private final String implementationPackage;
    private final List<Class<?>> contracts;
    private final HostServices host;
    private final List<Class<?>> hostContracts;
    private final Path cache;
    private final Executor cleanup;
    private final Map<GameExecution, Binding> bindings = new ConcurrentHashMap<>();
    private final ThreadLocal<Generation> current = new ThreadLocal<>();
    private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
    private final Set<Generation> retiring = ConcurrentHashMap.newKeySet();
    private final AtomicInteger liveGenerations = new AtomicInteger();
    private final Binding defaultBinding;
    private ModuleArtifact artifact;
    private boolean closed;

    private final class Generation {
        private final ModuleArtifact source;
        private final BusinessModule module;
        private final Map<Class<?>, Object> services;
        private final AtomicInteger references = new AtomicInteger(1);

        private Generation(ModuleArtifact source, HostServices services) {
            this.source = source;
            this.module = source.create(services);
            try {
                this.services = Map.copyOf(module.services());
                if (!this.services.keySet().equals(Set.copyOf(contracts)))
                    throw new IllegalArgumentException(I18n.message("error.module.services_incomplete", id));
                for (Class<?> contract : contracts) contract.cast(this.services.get(contract));
            } catch (Throwable error) {
                try { module.close(); } finally { releaseArtifact(source); }
                throw error;
            }
            liveGenerations.incrementAndGet();
        }

        private void retire() { retiring.add(this); release(); }

        private void release() {
            if (references.decrementAndGet() != 0) return;
            Runnable dispose = () -> {
                try { module.close(); }
                catch (Throwable error) { cleanupFailure.compareAndSet(null, error); }
                finally { releaseArtifact(source); liveGenerations.decrementAndGet(); retiring.remove(this); }
            };
            try { cleanup.execute(dispose); }
            catch (RuntimeException rejected) { cleanupFailure.compareAndSet(null, rejected); dispose.run(); }
        }
    }

    private final class Binding {
        private final GameExecution execution;
        private final DefaultVersionGate gate = new DefaultVersionGate();
        private final Runnable safePoint;
        private Generation generation;

        private Binding(GameExecution execution, Runnable safePoint, ModuleArtifact source) {
            this.execution = execution; this.safePoint = safePoint;
            this.generation = new Generation(source, host.with(VersionGate.class, gate));
        }
        private synchronized Generation acquire() {
            if (generation == null) throw new IllegalStateException(I18n.message("error.module.binding_closed"));
            generation.references.incrementAndGet(); return generation;
        }
        private synchronized void install(Generation next) {
            if (generation == null) throw new IllegalStateException(I18n.message("error.module.binding_closed"));
            Generation previous = generation;
            generation = next;
            gate.onReload();
            previous.retire();
        }
        private synchronized String digest() { return generation == null ? "closed" : generation.source.digest(); }
        private synchronized void dispose() {
            if (generation != null) { generation.retire(); generation = null; }
        }
    }

    /** 令牌超时后清掉候选引用；晚到的排队任务不会偷偷切换版本。 */
    private final class SwitchOperation {
        private final Binding binding;
        private Generation next;
        private boolean applied;
        private SwitchOperation(Binding binding, Generation next) { this.binding = binding; this.next = next; }
        private boolean run() {
            synchronized (this) { if (next == null) return false; }
            binding.safePoint.run();
            synchronized (this) {
                if (next == null) return false;
                binding.install(next); next = null; applied = true; return true;
            }
        }
        private synchronized boolean cancel() {
            if (next != null) { next.retire(); next = null; }
            return applied;
        }
    }

    public ModuleRuntime(String id, String implementationPackage, List<Class<?>> contracts, HostServices host,
                         Path initial, Path cache, Executor cleanup) throws Exception {
        this.id = id; this.implementationPackage = implementationPackage; this.contracts = List.copyOf(contracts);
        this.host = host; this.cache = cache; this.cleanup = cleanup;
        var required = new HashSet<Class<?>>(host.values().keySet());
        required.add(VersionGate.class);
        this.hostContracts = required.stream().sorted(Comparator.comparing(Class::getName)).toList();
        artifact = ModuleArtifact.prepare(initial, cache, id, implementationPackage, contracts, hostContracts);
        try { defaultBinding = new Binding(null, () -> { }, artifact); }
        catch (Throwable failure) { artifact.close(); throw failure; }
    }

    /** 在接入角色前装配一次；业务只能持有 service 返回的稳定代理。 */
    public synchronized void bind(GameExecution execution, Runnable safePoint) {
        requireOpen();
        if (bindings.containsKey(execution)) throw new IllegalStateException(I18n.message("error.module.binding_duplicate"));
        Binding binding = new Binding(execution, safePoint, artifact);
        bindings.put(execution, binding);
        try {
            execution.bindVersionGate(binding.gate);
            execution.addOperationScope(action -> scope(binding, () -> { action.run(); return null; }));
            execution.onClose(() -> { Binding removed = bindings.remove(execution); if (removed != null) removed.dispose(); });
        } catch (RuntimeException failure) { bindings.remove(execution); binding.dispose(); throw failure; }
    }

    @SuppressWarnings("unchecked")
    public <T> T service(Class<T> contract) {
        if (!contract.isInterface() || !contracts.contains(contract)) throw new IllegalArgumentException(I18n.message("error.module.contract_unknown"));
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "LogicProxy[" + id + ":" + contract.getSimpleName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
            Supplier<Object> invoke = () -> {
                try { return method.invoke(current.get().services.get(contract), args); }
                catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException cause) throw cause;
                    if (failure.getCause() instanceof Error cause) throw cause;
                    throw new IllegalStateException(failure.getCause());
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            };
            if (current.get() != null) return invoke.get();
            Binding binding = GameExecution.current() == null ? null : bindings.get(GameExecution.current());
            return scope(binding == null ? defaultBinding : binding, invoke);
        });
    }

    private <T> T scope(Binding binding, Supplier<T> action) {
        Generation previous = current.get();
        if (previous != null) return action.get();
        Generation selected = binding.acquire();
        current.set(selected);
        try { return action.get(); }
        finally { current.remove(); selected.release(); }
    }

    public synchronized Update reload(Path source) throws Exception {
        requireOpen();
        if (GameExecution.inGameOperation()) throw new IllegalStateException(I18n.message("error.module.game_wait"));
        if (cleanupFailure.get() != null) throw new IllegalStateException(I18n.message("error.module.cleanup_failed"), cleanupFailure.get());
        if (!retiring.isEmpty()) throw new IllegalStateException(I18n.message("error.module.retiring"));
        ModuleArtifact loaded = ModuleArtifact.prepare(source, cache, id, implementationPackage, contracts, hostContracts);
        final ModuleArtifact candidate;
        if (loaded.digest().equals(artifact.digest())) { loaded.close(); candidate = artifact.retain(); }
        else candidate = loaded;
        List<Binding> targets = new ArrayList<>(bindings.values());
        targets.add(defaultBinding);
        if (!candidate.digest().equals(artifact.digest())
                && targets.stream().anyMatch(binding -> !binding.digest().equals(artifact.digest()))) {
            candidate.close();
            throw new IllegalStateException(I18n.message("error.module.partial"));
        }
        Map<Binding, Generation> prepared = new LinkedHashMap<>();
        try {
            for (Binding binding : targets) {
                if (!binding.digest().equals(candidate.digest()))
                    prepared.put(binding, new Generation(candidate, host.with(VersionGate.class, binding.gate)));
            }
        } catch (Throwable error) {
            prepared.values().forEach(Generation::retire); candidate.close(); throw error;
        }
        Map<String, String> results = new LinkedHashMap<>();
        for (Binding binding : targets) {
            String name = binding.execution == null ? "default" : binding.execution.name();
            Generation next = prepared.get(binding);
            if (next == null) { results.put(name, "APPLIED"); continue; }
            SwitchOperation operation = new SwitchOperation(binding, next);
            try {
                boolean applied = binding.execution == null ? operation.run()
                        : binding.execution.submitBarrier(operation::run).get(5, TimeUnit.SECONDS);
                results.put(name, applied ? "APPLIED" : "FAILED");
            } catch (Exception failure) {
                results.put(name, operation.cancel() ? "APPLIED" : "FAILED");
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        // 失败目标继续持有旧代，新创建目标采用候选；结果必须逐目标报告。
        ModuleArtifact previous = artifact; artifact = candidate; previous.close();
        return new Update(id, candidate.digest(), results);
    }

    public record Status(String activeDigest, Map<String,String> targets, int retiringGenerations, boolean cleanupFailed) {
        public Status { targets = Map.copyOf(targets); }
    }
    public synchronized Status status() {
        Map<String,String> targets = new LinkedHashMap<>();
        bindings.forEach((execution,binding) -> targets.put(execution.name(), binding.digest()));
        targets.put("default", defaultBinding.digest());
        return new Status(artifact.digest(), targets, retiring.size(), cleanupFailure.get() != null);
    }
    public synchronized String digest() { return artifact.digest(); }
    public int liveGenerations() { return liveGenerations.get(); }
    public Throwable cleanupFailure() { return cleanupFailure.get(); }
    private void requireOpen() { if (closed) throw new IllegalStateException(I18n.message("error.module.runtime_closed")); }
    private void releaseArtifact(ModuleArtifact source) {
        try { source.close(); } catch (Exception failure) { cleanupFailure.compareAndSet(null, failure); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; bindings.values().forEach(Binding::dispose); bindings.clear();
        defaultBinding.dispose(); releaseArtifact(artifact);
    }
}
