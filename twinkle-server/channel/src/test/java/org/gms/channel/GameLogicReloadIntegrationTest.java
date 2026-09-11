package org.gms.channel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.gms.concurrent.GameExecution;
import org.gms.domain.game.logic.CombatSystem;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.module.HostServices;
import org.gms.module.ModuleRuntime;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.packet.ByteArrayInPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 原来的攻击入口保持不变，只替换独立 JAR；同一个在线角色和怪物立即使用新伤害规则。 */
public class GameLogicReloadIntegrationTest {
    @TempDir public Path directory;

    @Test public void attackHandlerUsesReplacementWithoutRecreatingOnlineState() throws Exception {
        Path original = Path.of("../target/logic/game-logic.jar");
        Properties metadata = new Properties();
        try (var jar = new JarFile(original.toFile());
             var input = jar.getInputStream(jar.getJarEntry("META-INF/twinkle-module.properties"))) { metadata.load(input); }
        List<Class<?>> contracts = new java.util.ArrayList<>();
        for (String name : metadata.getProperty("contracts").split(",")) contracts.add(Class.forName(name));
        try (var owner = new GameExecution("real-attack", new DefaultVersionGate());
             var runtime = new ModuleRuntime("game-logic", "org.gms.logic.game.", contracts,
                     new HostServices(Map.of(GameDataProvider.class, GameDataProvider.fixed(Map.of(), Map.of()))),
                     variant(original, 11), directory.resolve("cache"), Runnable::run)) {
            runtime.bind(owner, () -> { });
            var map = new MapleMap(); map.setMapId(100);
            var session = new GameplayTestSession(1, map);
            var sessions = new PlayerSessionRegistry(owner);
            var players = new PlayerStorage(owner);
            var data = new MobData(100100); data.setMaxHp(1000);
            var monster = new MapleMonster(data); monster.setObjectId(123);
            owner.run(() -> { players.add(session.character); sessions.claim(1, session); map.addMonster(monster); });
            var handler = new AttackHandler(runtime.service(CombatSystem.class), sessions, false, false);
            owner.run(() -> handler.handle(session, attack()));
            assertThat(monster.getHp()).isEqualTo(989);
            runtime.reload(variant(original, 37));
            owner.run(() -> handler.handle(session, attack()));
            assertThat(monster.getHp()).isEqualTo(952);
            runtime.reload(variant(original, 53));
            owner.run(() -> handler.handle(session, attack()));
            assertThat(monster.getHp()).isEqualTo(899);
            assertThat(owner.call(() -> players.getById(1))).isSameAs(session.character);
            assertThat(sessions.get(1)).isSameAs(session);
            assertThat(map.getMonster(123)).isSameAs(monster);
            assertThat(runtime.liveGenerations()).isEqualTo(2);
        }
    }

    private static ByteArrayInPacket attack() {
        ByteBuffer bytes = ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 0).put((byte) 0x11).putInt(0).put(new byte[8]);
        bytes.put(new byte[5]).putInt(0).putInt(123).put(new byte[14]).putInt(9999);
        return new ByteArrayInPacket(bytes.array());
    }

    private Path variant(Path original, int damage) throws Exception {
        Path classes = directory.resolve("classes-" + damage); Files.createDirectories(classes);
        Path source = classes.resolve("DefaultCombatSystem.java");
        Files.writeString(source, """
                package org.gms.logic.game;
                import org.gms.domain.game.logic.CombatSystem;
                import org.gms.domain.game.spi.CharacterState;
                import org.gms.domain.game.spi.MonsterState;
                import org.gms.hotreload.versioned.*;
                public final class DefaultCombatSystem implements CombatSystem {
                    private final VersionGate gate;
                    public DefaultCombatSystem(VersionGate gate) { this.gate = gate; }
                    public DamageResult physicalAttack(CharacterState attacker, MonsterState monster, int attack) {
                        if (gate.decide(attacker) != VersionDecision.ALLOW) return DamageResult.blocked();
                        var result = monster.applyDamage(%d);
                        return new DamageResult(result.damage(), result.alive(), result.killed());
                    }
                }
                """.formatted(damage));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(), source.toString())).isZero();
        String replacement = "org/gms/logic/game/DefaultCombatSystem.class";
        Path result = directory.resolve("game-" + damage + ".jar");
        try (var input = new JarFile(original.toFile()); var output = new JarOutputStream(Files.newOutputStream(result))) {
            for (JarEntry entry : input.stream().filter(item -> !item.isDirectory()).toList()) {
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals(replacement)) Files.copy(classes.resolve(replacement), output);
                else try (var bytes = input.getInputStream(entry)) { bytes.transferTo(output); }
                output.closeEntry();
            }
        }
        return result;
    }
}
