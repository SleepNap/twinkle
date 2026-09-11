package org.gms.module;
import java.util.HashMap;

import org.gms.i18n.I18n;

import java.util.Map;

/** 宿主显式提供的稳定服务，不允许逻辑模块访问 DI 容器。 */
public record HostServices(Map<Class<?>, Object> values) {
    public HostServices { values = Map.copyOf(values); }
    public <T> T require(Class<T> contract) {
        Object value = values.get(contract);
        if (value == null) throw new IllegalArgumentException(I18n.message("error.module.host_service_missing", contract.getName()));
        return contract.cast(value);
    }
    public HostServices with(Class<?> contract, Object value) {
        var copy = new HashMap<>(values); copy.put(contract, value); return new HostServices(copy);
    }
}
