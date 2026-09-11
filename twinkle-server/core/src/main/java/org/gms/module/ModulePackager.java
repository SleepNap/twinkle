package org.gms.module;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.Arrays;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Maven 在 process-classes 阶段生成独立逻辑包及其编译契约摘要。 */
public final class ModulePackager {
    private ModulePackager() { }
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]), output = Path.of(args[1]);
        Properties manifest = new Properties();
        manifest.setProperty("module", args[2]);
        manifest.setProperty("factory", args[3]);
        manifest.setProperty("package", args[4]);
        manifest.setProperty("contracts", args[5]);
        var contracts = new ArrayList<Class<?>>();
        for (String name : args[5].split(",")) contracts.add(Class.forName(name, false, ModulePackager.class.getClassLoader()));
        String hosts = Arrays.stream(args[6].split(",")).filter(name -> !name.isBlank()).sorted()
                .collect(Collectors.joining(","));
        manifest.setProperty("hostContracts", hosts);
        for (String name : hosts.split(",")) if (!name.isBlank())
            contracts.add(Class.forName(name, false, ModulePackager.class.getClassLoader()));
        manifest.setProperty("fingerprint", ContractFingerprint.calculate(contracts));
        Path metadata = classes.resolve("META-INF/twinkle-module.properties");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, manifest.stringPropertyNames().stream().sorted()
                .map(key -> key + "=" + manifest.getProperty(key) + "\n").collect(Collectors.joining()),
                StandardCharsets.US_ASCII);
        Files.createDirectories(output.getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(output)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(classes.relativize(path).toString().replace('\\', '/'));
                entry.setTime(0); jar.putNextEntry(entry); Files.copy(path, jar); jar.closeEntry();
            }
        }
    }
}
