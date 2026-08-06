package org.gms.domain.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ScriptRepository 数据源定位 + reload 增减改（架构 6.4 / M2-4 第 3/4 项）。
 */
class ScriptRepositoryTest {

    @Test
    void constructorRejectsMissingDirectory(@TempDir Path empty) {
        Path missing = empty.resolve("nope");
        assertThatThrownBy(() -> new ScriptRepository(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void scanLoadsAllJsFilesRecursively(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("nps"));
        Files.createDirectories(root.resolve("quests"));
        Files.writeString(root.resolve("nps/100.js"), "// npc 100");
        Files.writeString(root.resolve("quests/intro.js"), "// intro quest");
        Files.writeString(root.resolve("README.md"), "ignored");

        ScriptRepository repo = new ScriptRepository(root);

        assertThat(repo.loadAll()).hasSize(2);
        assertThat(repo.loadAll().get("nps/100").content()).isEqualTo("// npc 100");
        assertThat(repo.loadAll().get("quests/intro").content()).contains("intro");
    }

    @Test
    void reloadDetectsAddModifyRemove(@TempDir Path root) throws Exception {
        Path a = root.resolve("a.js");
        Files.writeString(a, "var a = 1;");
        Path b = root.resolve("b.js");
        Files.writeString(b, "var b = 2;");

        ScriptRepository repo = new ScriptRepository(root);
        assertThat(repo.loadAll()).hasSize(2);

        // 修改 a（修改需要等 mtime 变化）
        Files.writeString(a, "var a = 99;");
        // 新增 c
        Files.writeString(root.resolve("c.js"), "var c = 3;");
        // 删除 b
        Files.delete(b);
        // 等 mtime 跨越至少 1ms（Files.getLastModifiedTime 在某些 FS 精度有限）
        Thread.sleep(50);

        int changed = repo.reload();
        assertThat(changed).isEqualTo(3);                  // 修改 + 新增 + 删除
        assertThat(repo.loadAll()).hasSize(2);
        assertThat(repo.loadAll().get("a").content()).isEqualTo("var a = 99;");
        assertThat(repo.loadAll()).containsKey("c");
        assertThat(repo.loadAll()).doesNotContainKey("b");
    }

    @Test
    void reloadIsIdempotentWhenNothingChanges(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("stable.js"), "// unchanged");
        ScriptRepository repo = new ScriptRepository(root);

        int first = repo.reload();
        int second = repo.reload();
        assertThat(first).isZero();
        assertThat(second).isZero();
    }
}
