package org.gms.module;

/** 由隔离加载器实例化的纯装配入口。 */
public interface BusinessModuleFactory {
    public BusinessModule create(HostServices host);
}
