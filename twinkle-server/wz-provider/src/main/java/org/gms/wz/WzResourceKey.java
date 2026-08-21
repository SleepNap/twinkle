package org.gms.wz;

import java.util.Objects;

/** 类型安全的 WZ 资源标识；名称是跨模块稳定契约。 */
public record WzResourceKey<T>(String name) {

    public WzResourceKey {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("WZ resource name must not be blank");
        }
    }
}
