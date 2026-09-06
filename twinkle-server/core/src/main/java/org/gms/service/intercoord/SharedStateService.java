package org.gms.service.intercoord;

import java.util.Map;
import java.util.Optional;

/** Coordinator 内部的带版本共享状态；业务不得传递任意 Java Object。 */
public interface SharedStateService {
    Optional<StoreEntry> read(String key);
    long write(String key, StoreValue value, long expectedVersion);
    long increment(String key, long delta);
    Map<String, StoreEntry> storeSnapshot();

    record StoreEntry(StoreValue value, long version) {
    }

    /** 稳定、可演进的跨进程状态信封。 */
    record StoreValue(String type, int schemaVersion, String payload) {
        public StoreValue {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
            if (schemaVersion <= 0) schemaVersion = 1;
        }

        public StoreValue(String type, String payload) {
            this(type, 1, payload);
        }

        public static StoreValue text(String value) { return new StoreValue("text", 1, value); }
        public static StoreValue number(long value) { return new StoreValue("long", 1, Long.toString(value)); }
        public static StoreValue json(String json) { return new StoreValue("json", 1, json); }

        public long asLong() {
            if (!"long".equals(type)) throw new IllegalStateException("Store value is not a long");
            return Long.parseLong(payload);
        }
    }
}
