package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.replaceable.ProgressionSystem;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.resource.SkillResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WZ 技能效果、等级校验与 v83 回包布局。 */
public class SkillGameplayTest {
    @Test public void activeSkillUsesWzLevelAndEncodesExactBuffMask(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("Skill.wz")).resolve("100.img.xml");
        Files.writeString(file, """
                <imgdir name="100.img"><imgdir name="skill"><imgdir name="1001003"><imgdir name="level">
                <imgdir name="1"><int name="time" value="75"/><int name="mpCon" value="8"/><int name="pdd" value="2"/></imgdir>
                </imgdir></imgdir></imgdir></imgdir>
                """);
        var resources = new WzResourceRegistry(root, List.of(new SkillResourceLoader()), Runnable::run);
        var handler = new ActiveSkillHandler(resources, new ProgressionSystem(new DefaultVersionGate()),
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
        var session = new GameplayTestSession(1, new MapleMap()); session.character.setMp(30);
        session.character.putSkill(new SkillEntry(1001003, 1, 0, -1));
        handler.handle(session, cast(1001003, 20));
        assertThat(session.character.getMp()).isEqualTo(30);
        session.sent.clear(); handler.handle(session, cast(1001003, 1));
        var response = new ByteArrayInPacket(session.sent.getFirst().getBytes());
        assertThat(response.readUnsignedShort()).isEqualTo(SendOpcode.GIVE_BUFF.getValue());
        assertThat(response.readLong()).isZero();
        assertThat(response.readLong()).isEqualTo(1L << 33);
        assertThat(response.readShort()).isEqualTo((short) 2);
        assertThat(response.readInt()).isEqualTo(1001003);
        assertThat(response.readInt()).isEqualTo(75_000);
        response.skip(9); assertThat(response.available()).isZero();
        assertThat(session.character.getMp()).isEqualTo(22);
    }
    @Test public void autoApCannotPartiallyApplyOrOverspend() {
        var handler = new AutoApHandler(new ProgressionSystem(new DefaultVersionGate()));
        var session = new GameplayTestSession(1, new MapleMap()); session.character.setAp(5);
        var request = new ByteArrayOutPacket(); request.writeLong(0); request.writeInt(0x40); request.writeInt(4);
        request.writeInt(0x80); request.writeInt(2);
        handler.handle(session, new ByteArrayInPacket(request.getBytes()));
        assertThat(session.character.getAp()).isEqualTo(5);
    }
    private static ByteArrayInPacket cast(int id, int level) {
        var packet = new ByteArrayOutPacket(); packet.writeInt(0); packet.writeInt(id); packet.writeByte(level);
        return new ByteArrayInPacket(packet.getBytes());
    }
}
