package org.gms.net.packet;

import org.gms.net.opcodes.RecvOpcode;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 收包分发注册表（架构 net-packet：可 register/replace，贡献点版本化，红线 13）。
 *
 * <p>按 opcode 值索引 handler 槽位。两条核心能力：
 * <ul>
 *   <li><b>register</b>：首次绑定某 opcode 的 handler。</li>
 *   <li><b>replace</b>：热替换已注册的 handler（可替换层装卸的运行时入口，
 *       同 opcode 仅允许更高版本覆盖，防回退旧版）。</li>
 * </ul>
 *
 * <p>版本化：每个注册项带整数版本。替换时要求新版本 &gt; 旧版本，保证贡献点
 * 只能前进、不可倒退（行为随版本单调演化的假设）。
 *
 * <p>线程安全：{@link ConcurrentHashMap}，tick 线程读、管理线程写。
 */
public final class HandlerRegistry {

    /** 单条注册项：handler + 声明的贡献点版本。 */
    public record Registration(int version, PacketHandler handler) {
    }

    private final ConcurrentMap<Integer, Registration> slots = new ConcurrentHashMap<>();

    /**
     * 首次注册（版本 1）。已存在同 opcode 时拒绝（用 {@link #replace} 覆盖）。
     */
    public void register(RecvOpcode opcode, PacketHandler handler) {
        register(opcode, handler, 1);
    }

    /**
     * 首次注册（指定版本）。已存在同 opcode 时拒绝。
     */
    public void register(RecvOpcode opcode, PacketHandler handler, int version) {
        Registration put = slots.putIfAbsent(opcode.getValue(), new Registration(version, handler));
        if (put != null) {
            throw new IllegalStateException("opcode 已注册: " + opcode + "（请用 replace 替换）");
        }
    }

    /**
     * 替换已注册的 handler。新版本必须高于旧版本（防回退）。
     */
    public void replace(RecvOpcode opcode, PacketHandler handler, int version) {
        slots.compute(opcode.getValue(), (key, existing) -> {
            if (existing != null && version <= existing.version()) {
                throw new IllegalStateException("替换版本须高于现版本: opcode=" + opcode
                        + ", 现有=" + existing.version() + ", 新=" + version);
            }
            return new Registration(version, handler);
        });
    }

    /**
     * 按 opcode 值取 handler。
     */
    public Optional<PacketHandler> find(int opcode) {
        Registration r = slots.get(opcode);
        return r == null ? Optional.empty() : Optional.of(r.handler());
    }

    /**
     * 已注册的贡献点数。
     */
    public int registeredCount() {
        return slots.size();
    }
}
