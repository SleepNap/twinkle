package org.gms.domain.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 脚本热重载 + 插件命名空间共存验证（架构 5.2 L2 / 7.1 Script 命名空间贡献点）。
 *
 * <p>验证：
 * <ul>
 *   <li>改脚本文件 → reload() → 新内容生效（L2 全链路）。</li>
 *   <li>插件命名空间挂载（mount）与目录脚本共存，reload 后插件脚本保留。</li>
 *   <li>unmount 移除插件命名空间（幂等）。</li>
 * </ul>
 */
class L2ScriptReloadTest {

    @TempDir
    Path tmp;

    @Test
    void reloadPicksUpChangedScriptContent() throws Exception {
        Path scriptDir = Files.createTempDirectory("twinkle-script-l2");
        Path npsDir = scriptDir.resolve("nps");
        Files.createDirectories(npsDir);
        Files.writeString(npsDir.resolve("100.js"), """
                function greeting() { return "hello-v1"; }
                greeting();
                """);

        ScriptRepository repo = new ScriptRepository(scriptDir);
        ScriptManager manager = new ScriptManager(new ScriptEngine(), repo);

        assertThat(manager.run("nps/100", Map.of()).orElseThrow().asString()).isEqualTo("hello-v1");

        // 改文件 → reload → 新内容生效
        Files.writeString(npsDir.resolve("100.js"), """
                function greeting() { return "hello-v2"; }
                greeting();
                """);
        int changed = manager.reload();
        assertThat(changed).isGreaterThan(0);
        assertThat(manager.run("nps/100", Map.of()).orElseThrow().asString()).isEqualTo("hello-v2");
    }

    @Test
    void pluginNamespaceMountCoexistsWithDirAndSurvivesReload() throws Exception {
        Path scriptDir = Files.createTempDirectory("twinkle-script-ns");
        Path npsDir = scriptDir.resolve("nps");
        Files.createDirectories(npsDir);
        Files.writeString(npsDir.resolve("1.js"), """
                function id() { return "base"; }
                id();
                """);

        ScriptRepository repo = new ScriptRepository(scriptDir);
        ScriptManager manager = new ScriptManager(new ScriptEngine(), repo);

        // 插件挂载 acme 命名空间
        ScriptSource mounted = new ScriptSource(scriptDir.resolve("acme/boss.js"),
                """
                function id() { return "plugin-boss"; }
                id();
                """, 0L);
        manager.mount("acme", Map.of("boss", mounted));

        // 命名空间脚本可跑
        assertThat(manager.run("acme/boss", Map.of()).orElseThrow().asString()).isEqualTo("plugin-boss");
        // 目录脚本仍可跑（共存）
        assertThat(manager.run("nps/1", Map.of()).orElseThrow().asString()).isEqualTo("base");

        // reload 目录后，插件命名空间保留
        manager.reload();
        assertThat(manager.run("acme/boss", Map.of()).orElseThrow().asString()).isEqualTo("plugin-boss");
        assertThat(manager.run("nps/1", Map.of()).orElseThrow().asString()).isEqualTo("base");

        // unmount 移除（幂等）
        manager.unmount("acme");
        manager.unmount("acme"); // 幂等
        assertThat(manager.run("acme/boss", Map.of())).isEmpty();
        assertThat(manager.run("nps/1", Map.of()).orElseThrow().asString()).isEqualTo("base");
    }
}
