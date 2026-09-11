package org.gms.channel;

import org.gms.logic.game.DefaultProgressionSystem;
import org.gms.logic.game.DefaultControlsSystem;
import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.control.ControlSettings.Macro;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlayerUtilityGameplayTest {
    @Test public void keymapChangesAreAtomicAndPersistAcrossCharacterReload() {
        var sessions = new PlayerSessionRegistry(); var player = joined(sessions, 1, new MapleMap());
        var versions = new DefaultVersionGate(); var handler = controls(sessions, versions);
        player.character.putSkill(new SkillEntry(1001003, 1, 0, -1));
        ControlSettings original = player.character.controls();
        var invalid = new ByteArrayOutPacket(); invalid.writeInt(0).writeInt(2)
                .writeInt(20).writeByte(1).writeInt(1001003).writeInt(90).writeByte(4).writeInt(1);
        handler.keys(player, input(invalid)); assertThat(player.character.controls()).isEqualTo(original);
        var valid = new ByteArrayOutPacket(); valid.writeInt(0).writeInt(1).writeInt(20).writeByte(1).writeInt(1001003);
        handler.keys(player, input(valid));
        assertThat(player.character.controls().bindings().get(20)).isEqualTo(new Binding(1, 1001003));
        assertThat(player.character.isDirty()).isTrue();
        var in = new ByteArrayInPacket(player.sent.getLast().getBytes());
        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.KEYMAP.getValue());
        assertThat(in.readByte()).isZero(); in.skip(20 * 5);
        assertThat(in.readByte()).isOne(); assertThat(in.readInt()).isEqualTo(1001003);
        assertThat(in.available()).isEqualTo(69 * 5);
        var assembler = new PlayerCharacterAssembler(versions);
        var saved = assembler.toData(player.character); assertThat(saved.getControlSettings()).isNotBlank();
        assertThat(assembler.fromData(saved).controls()).isEqualTo(player.character.controls());
        var unbind = new ByteArrayOutPacket(); unbind.writeInt(0).writeInt(1).writeInt(20).writeByte(0).writeInt(0);
        versions.onReload(); handler.keys(player, input(unbind));
        assertThat(player.character.controls().bindings().get(20)).isEqualTo(new Binding(1, 1001003));
    }

    @Test public void macroReplacementQuickSlotsAndStoredSnapshotRetainExactValues() {
        var sessions = new PlayerSessionRegistry(); var player = joined(sessions, 1, new MapleMap());
        var handler = controls(sessions, new DefaultVersionGate());
        player.character.putSkill(new SkillEntry(1001003, 1, 0, -1));
        var macro = new ByteArrayOutPacket(); macro.writeByte(1).writeString("准备战斗").writeByte(1)
                .writeInt(1001003).writeInt(0).writeInt(0);
        handler.macros(player, input(macro));
        assertThat(player.character.controls().macros()).containsExactly(new Macro("准备战斗", true, List.of(1001003, 0, 0)));
        var reply = new ByteArrayInPacket(player.sent.getLast().getBytes());
        assertThat(reply.readUnsignedShort()).isEqualTo(SendOpcode.MACRO_SYS_DATA_INIT.getValue());
        assertThat(reply.readByte()).isOne(); assertThat(reply.readString()).isEqualTo("准备战斗");
        assertThat(reply.readByte()).isOne(); assertThat(reply.readInt()).isEqualTo(1001003);
        assertThat(reply.readLong()).isZero(); assertThat(reply.available()).isZero();
        // 第二个宏被截断时，第一个也不能提交。
        var partial = new ByteArrayOutPacket(); partial.writeByte(2).writeString("不能写入").writeByte(0)
                .writeInt(1001003).writeInt(0).writeInt(0);
        byte[] truncated = partial.getBytes();
        handler.macros(player, new ByteArrayInPacket(truncated));
        assertThat(player.character.controls().macros()).containsExactly(new Macro("准备战斗", true, List.of(1001003, 0, 0)));
        var quick = new ByteArrayOutPacket(); List.of(1, 2, 3, 4, 5, 6, 7, 89).forEach(quick::writeInt);
        handler.quickSlots(player, input(quick));
        reply = new ByteArrayInPacket(player.sent.getLast().getBytes());
        assertThat(reply.readUnsignedShort()).isEqualTo(SendOpcode.QUICKSLOT_INIT.getValue()); assertThat(reply.readByte()).isOne();
        assertThat(reply.available()).isEqualTo(32);
        var settings = player.character.controls();
        assertThat(ControlSettingsCodec.decode(ControlSettingsCodec.encode(settings))).isEqualTo(settings);
        assertThatThrownBy(() -> ControlSettingsCodec.decode("AQ==")).isInstanceOf(IllegalArgumentException.class);
        var empty = new ByteArrayOutPacket(); empty.writeByte(0); handler.macros(player, input(empty));
        assertThat(player.character.controls().macros()).isEmpty();
        assertThat(player.character.controls().quickSlots()).containsExactly(1, 2, 3, 4, 5, 6, 7, 89);
    }

    @Test public void unknownSkillInvalidQuickSlotsAndOldConnectionsCannotChangeSettings() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap(); var old = joined(sessions, 1, map);
        var handler = controls(sessions, new DefaultVersionGate());
        var unknown = new ByteArrayOutPacket(); unknown.writeByte(1).writeString("技能").writeByte(0)
                .writeInt(9999999).writeInt(0).writeInt(0);
        handler.macros(old, input(unknown)); assertThat(old.character.controls().macros()).isEmpty();
        var badKeys = new ByteArrayOutPacket(); for (int i = 0; i < 8; i++) badKeys.writeInt(i == 7 ? 256 : i);
        handler.quickSlots(old, input(badKeys)); assertThat(old.character.controls().quickSlots()).isEmpty();
        joined(sessions, 1, map); old.sent.clear(); handler.quickSlots(old, input(badKeys));
        assertThat(old.sent).isEmpty();
    }

    @Test public void characterDetailsOnlyExposeSameMapMemberAndEncodeMedals() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var viewer = joined(sessions, 1, map); var target = joined(sessions, 2, map);
        var remote = joined(sessions, 3, new MapleMap());
        target.character.setLevel(30); target.character.setJob(200); target.character.setFame(-10);
        Equip medal = new Equip(1142000); medal.setPosition((short) -49);
        target.character.getInventory(InventoryType.EQUIP).putAtSlot((short) -49, medal);
        var quest = new QuestStatus(29001); quest.setState(QuestStatus.State.COMPLETED); target.character.putQuest(quest);
        var handler = new CharacterInfoHandler(sessions);
        var request = new ByteArrayOutPacket(); request.writeInt(0).writeInt(3);
        handler.handle(viewer, input(request)); assertThat(viewer.sent).isEmpty();
        request = new ByteArrayOutPacket(); request.writeInt(0).writeInt(2); handler.handle(viewer, input(request));
        var in = new ByteArrayInPacket(viewer.sent.getFirst().getBytes());
        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.CHAR_INFO.getValue()); assertThat(in.readInt()).isEqualTo(2);
        assertThat(in.readByte()).isEqualTo((byte) 30); assertThat(in.readShort()).isEqualTo((short) 200);
        assertThat(in.readShort()).isEqualTo((short) -10); assertThat(in.readByte()).isZero();
        assertThat(in.readString()).isEmpty(); assertThat(in.readString()).isEmpty(); in.skip(24);
        assertThat(in.readInt()).isEqualTo(1142000); assertThat(in.readShort()).isEqualTo((short) 1);
        assertThat(in.readShort()).isEqualTo((short) 29001); assertThat(in.available()).isZero();
        assertThat(remote.sent).isEmpty();
    }

    private static ControlsHandler controls(PlayerSessionRegistry sessions, DefaultVersionGate versions) {
        var progression = new DefaultProgressionSystem(versions);
        return new ControlsHandler(sessions, new DefaultControlsSystem(progression::accepts, GameDataProvider.fixed(Map.of(), Map.of())),
                Clock.systemUTC());
    }
    private static GameplayTestSession joined(PlayerSessionRegistry sessions, long id, MapleMap map) {
        var session = new GameplayTestSession(id, map); sessions.claim(id, session); return session;
    }
    private static ByteArrayInPacket input(ByteArrayOutPacket out) { return new ByteArrayInPacket(out.getBytes()); }
}
