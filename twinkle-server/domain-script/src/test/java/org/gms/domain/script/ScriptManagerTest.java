package org.gms.domain.script;

import org.gms.domain.script.host.Cm;
import org.gms.domain.script.host.Em;
import org.gms.domain.script.host.Im;
import org.gms.domain.script.host.Qm;
import org.gms.domain.script.host.Rm;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScriptManager：宿主契约注入（cm/qm/em/rm/im）+ L2 热重载不影响进行中。
 */
class ScriptManagerTest {

    private Path scriptDir;
    private ScriptEngine engine;
    private ScriptRepository repo;
    private ScriptManager manager;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        scriptDir = root.resolve("scripts");
        Files.createDirectories(scriptDir);
        Files.writeString(scriptDir.resolve("hello.js"), "cm.getName() + ':' + cm.getLevel()");
        engine = new ScriptEngine();
        repo = new ScriptRepository(scriptDir);
        manager = new ScriptManager(engine, repo);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void runsScriptWithHostBindings() {
        Map<String, Object> bindings = Map.of("cm", (Cm) new StubCm("alice", 50));
        Optional<Value> result = manager.run("hello", bindings);
        assertThat(result).isPresent();
        assertThat(result.get().asString()).isEqualTo("alice:50");
    }

    @Test
    void allFiveContractsInjectedAutomatically() throws IOException {
        Files.writeString(scriptDir.resolve("multi.js"), """
                (typeof cm !== 'undefined') &&
                (typeof qm !== 'undefined') &&
                (typeof em !== 'undefined') &&
                (typeof rm !== 'undefined') &&
                (typeof im !== 'undefined')
                """);
        manager.reload();   // 快照更新，纳入新写入的 multi.js
        Map<String, Object> bindings = Map.of(
                "cm", (Cm) new StubCm("bob", 1),
                "qm", (Qm) new StubQm(),
                "em", (Em) new StubEm(),
                "rm", (Rm) new StubRm(),
                "im", (Im) new StubIm());
        Value result = manager.run("multi", bindings).orElseThrow();
        assertThat(result.asBoolean()).isTrue();
    }

    @Test
    void unknownKeyReturnsEmpty() {
        assertThat(manager.run("missing", Map.of())).isEmpty();
    }

    @Test
    void reloadAfterEditPicksUpNewCode() throws Exception {
        Map<String, Object> bindings = Map.of("cm", (Cm) new StubCm("x", 1));
        assertThat(manager.run("hello", bindings).orElseThrow().asString()).isEqualTo("x:1");

        Files.writeString(scriptDir.resolve("hello.js"), "cm.getName() + '!'");
        Thread.sleep(50);   // 等 mtime 跨越
        manager.reload();

        assertThat(manager.run("hello", bindings).orElseThrow().asString()).isEqualTo("x!");
    }

    @Test
    void reloadDoesNotAffectInFlightScript(@TempDir Path root) throws Exception {
        // 模拟"进行中的脚本"：reload 前后 eval 同一 key，结果应基于调用时刻的快照内容
        Path scriptDir2 = root.resolve("scripts2");
        Files.createDirectories(scriptDir2);
        Files.writeString(scriptDir2.resolve("ping.js"), "1 + 1");
        ScriptRepository r2 = new ScriptRepository(scriptDir2);
        ScriptManager m2 = new ScriptManager(engine, r2);

        // 拿一次
        Value v1 = m2.run("ping", Map.of()).orElseThrow();
        assertThat(v1.asInt()).isEqualTo(2);

        // 修改文件 + reload，新调用应得 3
        Files.writeString(scriptDir2.resolve("ping.js"), "1 + 2");
        Thread.sleep(50);
        m2.reload();
        Value v2 = m2.run("ping", Map.of()).orElseThrow();
        assertThat(v2.asInt()).isEqualTo(3);

        // 旧调用持有的 v1 仍为 2（不可变快照，eval 不持有共享引用）
        assertThat(v1.asInt()).isEqualTo(2);
    }

    /* ===================== 宿主契约存根（public 类 + public 方法，GraalVM 反射可见） ===================== */

    public record StubCm(String name, int level) implements Cm {
        @Override public String getName() { return name; }
        @Override public int getLevel() { return level; }
        @Override public int getJob() { return 0; }
        @Override public int getHp() { return 100; }
        @Override public int getMaxHp() { return 100; }
        @Override public int getMp() { return 50; }
        @Override public int getMaxMp() { return 50; }
        @Override public int getMapId() { return 100000000; }
        @Override public long getId() { return 1L; }
    }

    public static final class StubQm implements Qm {
        @Override public boolean isStarted(int id) { return false; }
        @Override public boolean isCompleted(int id) { return false; }
        @Override public void startQuest(int id) {}
        @Override public void completeQuest(int id) {}
    }

    public static final class StubEm implements Em {
        @Override public void setMapTimer(int s) {}
        @Override public void broadcastMessage(String m) {}
    }

    public static final class StubRm implements Rm {
        @Override public void giveExp(int a) {}
        @Override public void giveMeso(int a) {}
    }

    public static final class StubIm implements Im {
        @Override public void giveItem(int id, int q) {}
        @Override public int takeItem(int id, int q) { return 0; }
        @Override public int getItemCount(int id) { return 0; }
    }
}
