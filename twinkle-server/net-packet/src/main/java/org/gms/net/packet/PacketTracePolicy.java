package org.gms.net.packet;

import org.gms.diagnostics.PacketTrace;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.opcodes.SendOpcode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 封包监听的协议目录、默认降噪项与强制敏感项。 */
public final class PacketTracePolicy {

    private static final Set<String> SENSITIVE_INBOUND = Set.of(
            "LOGIN_PASSWORD", "REGISTER_PIN", "AFTER_LOGIN", "REGISTER_PIC",
            "CHAR_SELECT_WITH_PIC", "VIEW_ALL_PIC_REGISTER", "VIEW_ALL_WITH_PIC");

    private static final Set<String> DEFAULT_EXCLUDED = Set.of(
            "MOVE_LIFE", "MOVE_MONSTER", "MOVE_MONSTER_RESPONSE", "MOVE_PLAYER",
            "MOVE_PET", "MOVE_SUMMON", "MOVE_DRAGON", "GENERAL_CHAT", "MULTI_CHAT",
            "MULTICHAT", "WHISPER", "SPOUSE_CHAT", "MESSENGER", "PET_CHAT", "CHATTEXT",
            "CHATTEXT1");

    private static final Map<Integer, String> INBOUND_NAMES = names(RecvOpcode.values());
    private static final Map<Integer, String> OUTBOUND_NAMES = names(SendOpcode.values());
    private static final PacketTrace.Catalog CATALOG = buildCatalog();

    public static String opcodeName(PacketTrace.Direction direction, int opcode) {
        String name = (direction == PacketTrace.Direction.INBOUND ? INBOUND_NAMES : OUTBOUND_NAMES).get(opcode);
        return name == null ? String.format(Locale.ROOT, "UNKNOWN_0x%04X", opcode & 0xFFFF) : name;
    }

    public static String opcodeHex(int opcode) {
        return String.format(Locale.ROOT, "0X%04X", opcode & 0xFFFF);
    }

    public static boolean isSensitive(PacketTrace.Direction direction, String opcodeName) {
        return direction == PacketTrace.Direction.INBOUND && SENSITIVE_INBOUND.contains(opcodeName);
    }

    public static PacketTrace.Catalog catalog() {
        return CATALOG;
    }

    private static Map<Integer, String> names(RecvOpcode[] values) {
        Map<Integer, String> result = new HashMap<>();
        for (RecvOpcode opcode : values) {
            result.putIfAbsent(opcode.getValue(), opcode.name());
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, String> names(SendOpcode[] values) {
        Map<Integer, String> result = new HashMap<>();
        for (SendOpcode opcode : values) {
            result.putIfAbsent(opcode.getValue(), opcode.name());
        }
        return Map.copyOf(result);
    }

    private static PacketTrace.Catalog buildCatalog() {
        List<PacketTrace.Opcode> opcodes = new ArrayList<>();
        INBOUND_NAMES.forEach((value, name) -> opcodes.add(new PacketTrace.Opcode(
                PacketTrace.Direction.INBOUND, value, name,
                SENSITIVE_INBOUND.contains(name), DEFAULT_EXCLUDED.contains(name))));
        OUTBOUND_NAMES.forEach((value, name) -> opcodes.add(new PacketTrace.Opcode(
                PacketTrace.Direction.OUTBOUND, value, name,
                false, DEFAULT_EXCLUDED.contains(name))));
        opcodes.sort(Comparator.comparing(PacketTrace.Opcode::direction)
                .thenComparingInt(PacketTrace.Opcode::value));
        return new PacketTrace.Catalog(opcodes, DEFAULT_EXCLUDED, SENSITIVE_INBOUND);
    }

    private PacketTracePolicy() {
    }
}
