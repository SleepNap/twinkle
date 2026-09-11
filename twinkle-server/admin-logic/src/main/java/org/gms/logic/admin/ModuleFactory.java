package org.gms.logic.admin;
import java.util.Map;
import org.gms.module.*;
import org.gms.persistence.repo.*;
import org.gms.service.admin.AdminService;
import org.gms.httpapi.application.admin.AccountOperations;
import org.gms.httpapi.admin.AdminAccessPolicy;
/** 显式创建本代业务对象，不注册线程、不持有在线状态。 */
public final class ModuleFactory implements BusinessModuleFactory {
    public ModuleFactory() { }
    @Override public BusinessModule create(HostServices host) {
        Map<Class<?>,Object> services = Map.ofEntries(
                Map.entry(AccountOperations.class, new DefaultAccountOperations(host.require(GameAccountRepository.class), host.require(AccountDeletionRepository.class), host.require(PlayerCharacterRepository.class), host.require(AdminService.class))),
                Map.entry(AdminAccessPolicy.class, new DefaultAdminAccessPolicy()));
        return () -> services;
    }
}
