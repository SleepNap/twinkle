package org.gms.module;
import java.util.ArrayList;

import org.gms.i18n.I18n;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/** 内容寻址的隔离逻辑制品；自有类不回退宿主，宿主类不从逻辑包加载。 */
public final class ModuleArtifact implements AutoCloseable {
    private final URLClassLoader loader;
    private final BusinessModuleFactory factory;
    private final String digest;
    private int references = 1;

    private ModuleArtifact(URLClassLoader loader, BusinessModuleFactory factory, String digest) {
        this.loader = loader; this.factory = factory; this.digest = digest;
    }

    public static ModuleArtifact prepare(Path source, Path cache, String expectedModule,
                                         String expectedPackage, List<Class<?>> contracts, List<Class<?>> hostContracts) throws Exception {
        if (!Files.isRegularFile(source) || Files.size(source) > 64L * 1024 * 1024)
            throw new IOException(I18n.message("error.module.artifact_invalid"));
        Files.createDirectories(cache);
        Path staged = Files.createTempFile(cache, "candidate-", ".jar");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            String digest;
            try (var in = Files.newInputStream(staged)) {
                var sha = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
                digest = HexFormat.of().formatHex(sha.digest());
            }
            Path immutable = cache.resolve(digest + ".jar");
            if (!Files.exists(immutable)) Files.move(staged, immutable);
            else if (Files.mismatch(staged, immutable) != -1) throw new IOException(I18n.message("error.module.cache_mismatch"));
            Properties metadata = new Properties();
            var fingerprintTypes = new ArrayList<Class<?>>(contracts);
            fingerprintTypes.addAll(hostContracts);
            try (var jar = new JarFile(immutable.toFile())) {
                var entry = jar.getJarEntry("META-INF/twinkle-module.properties");
                if (entry == null) throw new IOException(I18n.message("error.module.metadata_missing"));
                try (var in = jar.getInputStream(entry)) { metadata.load(in); }
                if (!expectedModule.equals(metadata.getProperty("module"))
                        || !expectedPackage.equals(metadata.getProperty("package"))
                        || !ContractFingerprint.calculate(fingerprintTypes).equals(metadata.getProperty("fingerprint"))
                        || !String.join(",", hostContracts.stream().map(Class::getName).sorted().toList()).equals(metadata.getProperty("hostContracts"))
                        || !String.join(",", contracts.stream().map(Class::getName).toList()).equals(metadata.getProperty("contracts")))
                    throw new IOException(I18n.message("error.module.contract_mismatch"));
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.startsWith(expectedPackage.replace('.', '/')))
                        throw new IOException(I18n.message("error.module.foreign_class", name));
                }
            }
            String factoryName = metadata.getProperty("factory", "");
            if (!factoryName.startsWith(expectedPackage)) throw new IOException(I18n.message("error.module.factory_invalid"));
            URLClassLoader loader = new URLClassLoader(new URL[]{immutable.toUri().toURL()}, BusinessModule.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith("org.gms.logic.") && !name.startsWith(expectedPackage))
                            throw new ClassNotFoundException(I18n.message("error.module.foreign_class", name));
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = name.startsWith(expectedPackage)
                                ? findClass(name) : getParent().loadClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            };
            try {
                // 提前加载全部实现类，缺失类/签名错误在切换前暴露。
                try (var jar = new JarFile(immutable.toFile())) {
                    for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                        Class<?> type = Class.forName(entry.getName().replace('/', '.').replaceFirst("\\.class$", ""), false, loader);
                        type.getDeclaredMethods(); type.getDeclaredConstructors(); type.getDeclaredFields();
                    }
                }
                return new ModuleArtifact(loader, (BusinessModuleFactory) Class.forName(factoryName, true, loader)
                        .getConstructor().newInstance(), digest);
            } catch (Throwable failure) { loader.close(); throw failure; }
        } finally { Files.deleteIfExists(staged); }
    }

    public synchronized BusinessModule create(HostServices host) {
        if (references <= 0) throw new IllegalStateException(I18n.message("error.module.artifact_closed"));
        BusinessModule module = factory.create(host);
        references++;
        return module;
    }
    public String digest() { return digest; }
    public synchronized ModuleArtifact retain() {
        if (references <= 0) throw new IllegalStateException(I18n.message("error.module.artifact_closed"));
        references++; return this;
    }
    @Override public synchronized void close() throws IOException {
        if (references > 0 && --references == 0) loader.close();
    }
}
