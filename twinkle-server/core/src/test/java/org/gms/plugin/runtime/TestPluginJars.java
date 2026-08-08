package org.gms.plugin.runtime;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * 测试辅助：构建迷你插件 jar（manifest + 运行时编译的插件字节码）。
 *
 * <p>用 JDK {@link JavaCompiler} 在测试期把插件源码编译进临时目录，再连同 manifest 打包成 jar，
 * 让 PluginManager / PluginClassLoader 能真实加载插件类。
 */
final class TestPluginJars {

    private TestPluginJars() {
    }

    /**
     * 构建一个插件 jar（只含 manifest，无字节码）。
     */
    static Path writeManifestOnlyJar(Path dir, String jarName, String manifest) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/twinkle-plugin.properties"));
            jos.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    /**
     * 构建一个含字节码的插件 jar。
     *
     * @param dir         目标目录（jar 写到 {@code dir/jarName}）
     * @param jarName     jar 文件名（如 {@code com.acme.boss.jar}）
     * @param manifest    插件 manifest 全文
     * @param classSources 插件类源码：{@code 全限定类名 → 源码}，全部放进插件包的默认命名空间
     * @return jar 路径
     */
    static Path writePluginJarWithClasses(Path dir, String jarName, String manifest,
                                          java.util.Map<String, String> classSources) throws IOException {
        Path classesDir = Files.createTempDirectory("plugin-classes");
        // 编译所有源码
        for (var e : classSources.entrySet()) {
            String className = e.getKey();
            Path srcFile = classesDir.resolve(className.replace('.', '/') + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, e.getValue());
        }
        Path outputDir = Files.createTempDirectory("plugin-out");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("无系统 Java 编译器（需 JDK 而非 JRE）");
        }
        // 插件源码可能 import 宿主 SDK（org.gms.plugin.*），编译期需要完整测试 classpath
        String cp = System.getProperty("java.class.path");
        // 收集全部源文件路径传给 javac
        List<String> sourceFiles;
        try (var stream = Files.walk(classesDir)) {
            sourceFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .toList();
        }
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int rc = compiler.run(null, null, err,
                Stream.concat(Stream.of("-encoding", "UTF-8", "-d", outputDir.toString(), "-cp", cp),
                                sourceFiles.stream())
                        .toArray(String[]::new));
        if (rc != 0) {
            throw new IllegalStateException("测试插件源码编译失败 rc=" + rc
                    + "\n" + err.toString(java.nio.charset.StandardCharsets.UTF_8)
                    + "\nclasspath=" + cp);
        }

        Path jar = dir.resolve(jarName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/twinkle-plugin.properties"));
            jos.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
            // 把 outputDir 下全部 .class 打进 jar
            try (var stream = Files.walk(outputDir)) {
                for (Path f : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                    String entryName = outputDir.relativize(f).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(entryName));
                    jos.write(Files.readAllBytes(f));
                    jos.closeEntry();
                }
            }
        }
        return jar;
    }
}
