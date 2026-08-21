package org.gms.diagnostics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 单玩家临时封包监听的跨进程稳定 DTO。
 *
 * <p>抓包仅保存在目标频道进程的有界内存中，经 {@code AdminService} 投影到管理侧；
 * 不写日志、不落数据库。管理进程与频道进程分离时沿用同一组 DTO 走内部 RPC。
 */
public final class PacketTrace {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 4096;
    public static final int MIN_MAX_PAYLOAD_BYTES = 64;
    public static final int MAX_MAX_PAYLOAD_BYTES = 16 * 1024;
    public static final int MAX_PAGE_SIZE = 500;

    /** 封包相对服务端的方向。 */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /** INCLUDE 只保留所选类型；EXCLUDE 保留所选类型之外的封包。 */
    public enum FilterMode {
        INCLUDE,
        EXCLUDE
    }

    /** 可配置的监听条件。opcodeNames 使用协议枚举名或 {@code 0x1234}。 */
    public record Config(FilterMode mode, Set<Direction> directions,
                         Set<String> opcodeNames, int maxPayloadBytes) {
        public Config {
            mode = mode == null ? FilterMode.EXCLUDE : mode;
            directions = directions == null || directions.isEmpty()
                    ? Set.of(Direction.INBOUND, Direction.OUTBOUND)
                    : Set.copyOf(directions);
            opcodeNames = opcodeNames == null ? Set.of() : opcodeNames.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            maxPayloadBytes = Math.max(MIN_MAX_PAYLOAD_BYTES,
                    Math.min(MAX_MAX_PAYLOAD_BYTES, maxPayloadBytes));
        }
    }

    /** 协议类型目录项。sensitive=true 的类型无论过滤条件如何都不会采集。 */
    public record Opcode(Direction direction, int value, String name,
                         boolean sensitive, boolean defaultExcluded) {
    }

    /** 供控制台构造过滤器的完整目录与安全默认值。 */
    public record Catalog(List<Opcode> opcodes, Set<String> defaultExcluded,
                          Set<String> neverCaptured) {
        public Catalog {
            opcodes = List.copyOf(opcodes);
            defaultExcluded = Set.copyOf(defaultExcluded);
            neverCaptured = Set.copyOf(neverCaptured);
        }
    }

    /** 一条已脱离 Netty ByteBuf 生命周期的封包快照。payloadHex 含两字节 opcode。 */
    public record Event(long sequence, long timestampEpochMillis, Direction direction,
                        int opcode, String opcodeName, int packetLength,
                        int capturedLength, boolean truncated, String payloadHex) {
    }

    /**
     * 当前监听快照。configured=false 表示在线会话尚未创建过监听；enabled=false 表示已停止，
     * 此时历史窗口仍可读取，便于 GM 停止后继续判断。
     */
    public record Snapshot(boolean configured, boolean enabled, Config config,
                           long lastSequence, long droppedEvents, List<Event> events) {
        public Snapshot {
            events = List.copyOf(events);
        }

        public static Snapshot notConfigured() {
            return new Snapshot(false, false, null, 0, 0, List.of());
        }
    }

    private PacketTrace() {
    }
}
