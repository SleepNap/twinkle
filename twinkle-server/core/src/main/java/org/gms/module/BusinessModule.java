package org.gms.module;

import java.util.Map;

/** 一代完整业务实现；构造阶段不得操作在线状态或注册外部副作用。 */
public interface BusinessModule extends AutoCloseable {
    public Map<Class<?>, Object> services();
    @Override public default void close() { }
}
