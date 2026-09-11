package org.gms.logic.login;
import java.util.Map;
import org.gms.module.*;
import org.gms.persistence.repo.*;
import org.gms.login.LoginService;
/** 显式创建本代业务对象，不注册线程、不持有在线状态。 */
public final class ModuleFactory implements BusinessModuleFactory {
    public ModuleFactory() { }
    @Override public BusinessModule create(HostServices host) {
        Map<Class<?>,Object> services = Map.ofEntries(
                Map.entry(LoginService.class, new DefaultLoginService(host.require(GameAccountRepository.class), host.require(PlayerCharacterRepository.class), host.require(InventoryItemRepository.class))));
        return () -> services;
    }
}
