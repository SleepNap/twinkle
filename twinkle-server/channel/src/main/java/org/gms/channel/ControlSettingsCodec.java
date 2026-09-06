package org.gms.channel;

import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.control.ControlSettings.Macro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/** 自有存档格式：版本化二进制的 Base64 文本，与 v83 网络字节布局无关。 */
public final class ControlSettingsCodec {
    private ControlSettingsCodec() { }

    public static String encode(ControlSettings settings) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeByte(1); out.writeByte(settings.bindings().size());
            for (var entry : new TreeMap<>(settings.bindings()).entrySet()) {
                out.writeByte(entry.getKey()); out.writeByte(entry.getValue().type()); out.writeInt(entry.getValue().action());
            }
            out.writeByte(settings.macros().size());
            for (Macro macro : settings.macros()) {
                out.writeUTF(macro.name()); out.writeBoolean(macro.shout());
                for (int skill : macro.skills()) out.writeInt(skill);
            }
            out.writeByte(settings.quickSlots().size());
            for (int key : settings.quickSlots()) out.writeByte(key);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException error) { throw new IllegalStateException("Cannot encode control settings", error); }
    }

    public static ControlSettings decode(String saved) {
        if (saved == null || saved.isBlank()) return ControlSettings.defaults();
        if (saved.length() > 4096) throw new IllegalArgumentException("Control settings exceed storage limit");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(saved)));
            if (in.readUnsignedByte() != 1) throw new IllegalArgumentException("Unknown control settings version");
            var bindings = new HashMap<Integer, Binding>();
            int count = in.readUnsignedByte();
            if (count > 90) throw new IllegalArgumentException("Too many key bindings");
            for (int i = 0; i < count; i++) {
                int key = in.readUnsignedByte(); var binding = new Binding(in.readUnsignedByte(), in.readInt());
                if (bindings.put(key, binding) != null) throw new IllegalArgumentException("Duplicate stored key binding");
            }
            var macros = new ArrayList<Macro>(); count = in.readUnsignedByte();
            if (count > 5) throw new IllegalArgumentException("Too many macros");
            for (int i = 0; i < count; i++) macros.add(new Macro(in.readUTF(), in.readBoolean(),
                    List.of(in.readInt(), in.readInt(), in.readInt())));
            var quickSlots = new ArrayList<Integer>(); count = in.readUnsignedByte();
            if (count != 0 && count != 8) throw new IllegalArgumentException("Invalid quick slot count");
            for (int i = 0; i < count; i++) quickSlots.add(in.readUnsignedByte());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing control settings data");
            return new ControlSettings(bindings, macros, quickSlots);
        } catch (IOException error) { throw new IllegalArgumentException("Truncated control settings", error); }
    }
}
