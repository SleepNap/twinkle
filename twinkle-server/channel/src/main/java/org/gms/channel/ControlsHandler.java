package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.control.ControlSettings.Macro;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ControlsSystem;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 操作设置收包：完整解析后才提交；回发服务端有效配置修正客户端本地设置。 */
public final class ControlsHandler {
    private final PlayerSessionRegistry sessions;
    private final ControlsSystem system;
    private final Clock clock;

    public ControlsHandler(PlayerSessionRegistry sessions, ControlsSystem system, Clock clock) {
        this.sessions = sessions; this.system = system; this.clock = clock;
    }

    public void keys(PacketSession session, InPacket packet) {
        PlayerCharacter character = current(session);
        if (character == null) return;
        try {
            if (packet.available() < 8 || packet.readInt() != 0) return; // 宠物自动 HP/MP 药另案接入
            int count = packet.readInt();
            if (count < 0 || count > 90 || packet.available() != count * 9) return;
            var changes = new HashMap<Integer, Binding>();
            for (int i = 0; i < count; i++) {
                int key = packet.readInt(); int type = packet.readByte() & 255; int action = packet.readInt();
                Binding binding = new Binding(type, type == 0 ? 0 : action);
                if (changes.put(key, binding) != null) return;
            }
            system.changeKeys(character, changes, clock.millis());
        } catch (IllegalArgumentException invalid) {
            // 客户端类型/动作越界，保留原配置并在 finally 回传。
        } finally { session.send(PlayerUtilityPackets.keymap(character.controls())); }
    }

    public void macros(PacketSession session, InPacket packet) {
        PlayerCharacter character = current(session);
        if (character == null) return;
        try {
            if (packet.available() < 1) return;
            int count = packet.readByte() & 255;
            if (count > 5) return;
            var macros = new ArrayList<Macro>();
            for (int i = 0; i < count; i++) {
                if (packet.available() < 2) return;
                int bytes = packet.readUnsignedShort();
                if (bytes > 24 || packet.available() < bytes + 13) return;
                String name = new String(packet.readBytes(bytes), InPacket.DEFAULT_CHARSET);
                int shout = packet.readByte() & 255;
                if (shout > 1) return;
                macros.add(new Macro(name, shout == 1, List.of(packet.readInt(), packet.readInt(), packet.readInt())));
            }
            if (packet.available() != 0) return;
            system.changeMacros(character, macros, clock.millis());
        } catch (IllegalArgumentException invalid) {
            // 任何一项无效都不写入前面已解析的宏。
        } finally { session.send(PlayerUtilityPackets.macros(character.controls())); }
    }

    public void quickSlots(PacketSession session, InPacket packet) {
        PlayerCharacter character = current(session);
        if (character == null) return;
        if (packet.available() == 32) {
            var keys = new ArrayList<Integer>();
            for (int i = 0; i < 8; i++) keys.add(packet.readInt());
            system.changeQuickSlots(character, keys);
        }
        session.send(PlayerUtilityPackets.quickSlots(character.controls()));
    }

    public void initialize(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || sessions.get(character.getId()) != session) return;
        session.send(PlayerUtilityPackets.keymap(character.controls()));
        session.send(PlayerUtilityPackets.macros(character.controls()));
        session.send(PlayerUtilityPackets.quickSlots(character.controls()));
    }

    private PlayerCharacter current(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        return character != null && sessions.get(character.getId()) == session
                && session.getAttr("mapTransition") == null ? character : null;
    }
}
