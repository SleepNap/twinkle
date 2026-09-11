package org.gms.logic.query;
import java.util.Map;
import org.gms.module.*;
import org.gms.persistence.repo.*;
import org.gms.httpapi.application.query.CharacterQueries;
/** 显式创建本代业务对象，不注册线程、不持有在线状态。 */
public final class ModuleFactory implements BusinessModuleFactory {
    public ModuleFactory() { }
    @Override public BusinessModule create(HostServices host) {
        Map<Class<?>,Object> services = Map.ofEntries(
                Map.entry(CharacterQueries.class, new DefaultCharacterQueries(host.require(GameAccountRepository.class), host.require(PlayerCharacterRepository.class), host.require(InventoryItemRepository.class), host.require(QuestRepository.class), host.require(SkillRepository.class), host.require(BuddyListRepository.class))));
        return () -> services;
    }
}
