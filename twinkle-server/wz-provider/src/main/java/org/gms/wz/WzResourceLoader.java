package org.gms.wz;

import java.nio.file.Path;

/**
 * 一个可参与统一热重载的 WZ 资源加载单元。
 *
 * <p>新增资源只需把实现注册为 Bean；注册中心会自动将其纳入启动加载、原子换代和结果统计。
 */
public interface WzResourceLoader<T> {

    WzResourceKey<T> key();

    T load(Path wzRoot);

    default int entryCount(T resource) {
        return 0;
    }
}
