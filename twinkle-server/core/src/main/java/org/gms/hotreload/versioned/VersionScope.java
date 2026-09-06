package org.gms.hotreload.versioned;

import java.util.function.Supplier;

/** 一个已准入操作固定使用同一逻辑版本，作用域退出后清除，不能跨线程继承。 */
public final class VersionScope {
    private record Entry(VersionGate gate, long version) { }
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();
    private VersionScope() { }

    public static long effective(VersionGate gate, long published) {
        Entry entry = CURRENT.get();
        return entry != null && entry.gate() == gate ? entry.version() : published;
    }

    public static <T> T call(VersionGate gate, long version, Supplier<T> action) {
        Entry previous = CURRENT.get();
        CURRENT.set(new Entry(gate, version));
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
