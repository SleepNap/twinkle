package org.gms.logic.coordinator;
import java.util.Map;
import org.gms.module.*;

import org.gms.service.intercoord.ChannelSelectionPolicy;
/** 显式创建本代业务对象，不注册线程、不持有在线状态。 */
public final class ModuleFactory implements BusinessModuleFactory {
    public ModuleFactory() { }
    @Override public BusinessModule create(HostServices host) {
        Map<Class<?>,Object> services = Map.ofEntries(
                Map.entry(ChannelSelectionPolicy.class, new DefaultChannelSelectionPolicy()));
        return () -> services;
    }
}
