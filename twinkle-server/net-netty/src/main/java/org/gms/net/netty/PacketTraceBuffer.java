package org.gms.net.netty;

import org.gms.diagnostics.PacketTrace;
import org.gms.net.packet.PacketTracePolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** 单连接封包监听的有界内存环形窗口。 */
public final class PacketTraceBuffer {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int MAX_EVENTS = 2000;
    private static final long MAX_RETAINED_BYTES = 4L * 1024 * 1024;

    private final PacketTrace.Config config;
    private final Deque<CapturedEvent> events = new ArrayDeque<>();

    private volatile boolean enabled = true;
    private long sequence;
    private long droppedEvents;
    private long retainedBytes;

    public PacketTraceBuffer(PacketTrace.Config config) {
        this.config = config;
    }

    public boolean enabled() {
        return enabled;
    }

    public void capture(PacketTrace.Direction direction, byte[] packet) {
        if (!enabled || packet == null || packet.length < 2 || !config.directions().contains(direction)) {
            return;
        }
        int opcode = (packet[0] & 0xFF) | ((packet[1] & 0xFF) << 8);
        String opcodeName = PacketTracePolicy.opcodeName(direction, opcode);
        if (PacketTracePolicy.isSensitive(direction, opcodeName) || !matchesFilter(opcodeName, opcode)) {
            return;
        }
        int capturedLength = Math.min(packet.length, config.maxPayloadBytes());
        byte[] copy = Arrays.copyOf(packet, capturedLength);
        synchronized (this) {
            if (!enabled) {
                return;
            }
            CapturedEvent event = new CapturedEvent(++sequence, System.currentTimeMillis(), direction,
                    opcode, opcodeName, packet.length, copy);
            events.addLast(event);
            retainedBytes += copy.length;
            trim();
        }
    }

    public synchronized PacketTrace.Snapshot snapshot(long afterSequence, int requestedLimit) {
        int limit = Math.max(1, Math.min(PacketTrace.MAX_PAGE_SIZE, requestedLimit));
        List<CapturedEvent> selected = new ArrayList<>();
        if (afterSequence <= 0) {
            int skip = Math.max(0, events.size() - limit);
            int index = 0;
            for (CapturedEvent event : events) {
                if (index++ >= skip) {
                    selected.add(event);
                }
            }
        } else {
            for (CapturedEvent event : events) {
                if (event.sequence() > afterSequence) {
                    selected.add(event);
                    if (selected.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return new PacketTrace.Snapshot(true, enabled, config, sequence, droppedEvents,
                selected.stream().map(PacketTraceBuffer::toEvent).toList());
    }

    public PacketTrace.Snapshot stop() {
        enabled = false;
        return snapshot(0, PacketTrace.MAX_PAGE_SIZE);
    }

    private boolean matchesFilter(String opcodeName, int opcode) {
        boolean selected = config.opcodeNames().contains(opcodeName)
                || config.opcodeNames().contains(PacketTracePolicy.opcodeHex(opcode));
        return config.mode() == PacketTrace.FilterMode.INCLUDE ? selected : !selected;
    }

    private void trim() {
        while (events.size() > MAX_EVENTS || retainedBytes > MAX_RETAINED_BYTES) {
            CapturedEvent removed = events.removeFirst();
            retainedBytes -= removed.payload().length;
            droppedEvents++;
        }
    }

    private static PacketTrace.Event toEvent(CapturedEvent event) {
        return new PacketTrace.Event(event.sequence(), event.timestampEpochMillis(), event.direction(),
                event.opcode(), event.opcodeName(), event.packetLength(), event.payload().length,
                event.payload().length < event.packetLength(), toHex(event.payload()));
    }

    private static String toHex(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        char[] result = new char[bytes.length * 3 - 1];
        int cursor = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                result[cursor++] = ' ';
            }
            int value = bytes[i] & 0xFF;
            result[cursor++] = HEX[value >>> 4];
            result[cursor++] = HEX[value & 0x0F];
        }
        return new String(result);
    }

    private record CapturedEvent(long sequence, long timestampEpochMillis,
                                 PacketTrace.Direction direction, int opcode,
                                 String opcodeName, int packetLength, byte[] payload) {
    }
}
