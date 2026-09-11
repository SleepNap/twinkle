package org.gms.module;

import org.gms.i18n.I18n;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** 本进程已装配模块目录；候选与启动制品分开，校验失败不会污染下次启动。 */
public final class ModuleRegistry implements AutoCloseable {
    private final Path directory;
    private final Executor cleanup;
    private final Map<String, ModuleRuntime> runtimes = new LinkedHashMap<>();

    public ModuleRegistry(Path directory, Executor cleanup) {
        this.directory = directory.toAbsolutePath().normalize(); this.cleanup = cleanup;
    }

    public synchronized ModuleRuntime register(String id, String implementationPackage,
                                                List<Class<?>> contracts, HostServices host) throws Exception {
        if (!id.matches("[a-z]+-logic") || runtimes.containsKey(id)) throw new IllegalArgumentException(I18n.message("error.module.id_invalid", id));
        Path pointer = directory.resolve(".cache/" + id + "/active");
        Path initial = directory.resolve(id + ".jar");
        if (Files.exists(pointer)) {
            String digest = Files.readString(pointer).trim();
            if (!digest.matches("[0-9a-f]{64}")) throw new IllegalStateException(I18n.message("error.module.startup_corrupt", id));
            initial = pointer.resolveSibling(digest + ".jar");
        }
        ModuleRuntime runtime = new ModuleRuntime(id, implementationPackage, contracts, host, initial,
                directory.resolve(".cache/" + id), cleanup);
        runtimes.put(id, runtime); return runtime;
    }

    public synchronized boolean contains(String id) { return runtimes.containsKey(id); }
    public synchronized Map<String, String> versions() {
        Map<String, String> versions = new LinkedHashMap<>();
        runtimes.forEach((id, runtime) -> versions.put(id, runtime.digest())); return Map.copyOf(versions);
    }

    public synchronized Map<String, ModuleRuntime.Status> status() {
        Map<String, ModuleRuntime.Status> result = new LinkedHashMap<>();
        runtimes.forEach((id,runtime) -> result.put(id, runtime.status())); return Map.copyOf(result);
    }

    public synchronized ModuleRuntime.Update reload(String id) throws Exception {
        ModuleRuntime runtime = runtimes.get(id);
        if (runtime == null) throw new IllegalArgumentException(I18n.message("error.module.not_registered", id));
        ModuleRuntime.Update result = runtime.reload(directory.resolve("incoming/" + id + ".jar"));
        Path pointer = directory.resolve(".cache/" + id + "/active");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(pointer.getParent(), "active-", ".tmp");
            Files.writeString(temporary, result.digest());
            Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            // 已在线切换不能谎报整体失败；明确提示启动记录尚未确认。
            Map<String, String> targets = new LinkedHashMap<>(result.targets());
            targets.put("startup-record", "FAILED");
            return new ModuleRuntime.Update(id, result.digest(), targets);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* 不用临时文件清理错误覆盖已报告的切换结果。 */ }
            }
        }
        return result;
    }

    @Override public synchronized void close() { runtimes.values().forEach(ModuleRuntime::close); runtimes.clear(); }
}
