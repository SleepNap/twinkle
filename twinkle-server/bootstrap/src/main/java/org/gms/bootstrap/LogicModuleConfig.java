package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gms.concurrent.ThreadManager;
import org.gms.module.HostServices;
import org.gms.module.ModuleRegistry;
import org.gms.module.ModuleRuntime;
import org.gms.httpapi.admin.AdminAccessPolicy;
import org.gms.httpapi.application.admin.AccountOperations;
import org.gms.httpapi.application.query.CharacterQueries;
import org.gms.login.LoginService;
import org.gms.persistence.repo.*;
import org.gms.role.ManagementProcessCondition;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.ChannelSelectionPolicy;

/** 宿主只装配稳定代理，业务实现从独立制品加载。 */
@Factory
public class LogicModuleConfig {
    @Bean(preDestroy = "close") @Singleton
    public ModuleRegistry moduleRegistry(ThreadManager background,
            @Property(name = "twinkle.logic.path", defaultValue = "") String directory) throws Exception {
        Path location = Path.of(LogicModuleConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path logicDirectory = directory.isBlank()
                ? (java.nio.file.Files.isRegularFile(location) ? location.getParent().resolve("logic")
                    : location.getParent().getParent().getParent().resolve("target/logic"))
                : Path.of(directory);
        return new ModuleRegistry(logicDirectory, background);
    }

    @Bean @Context @Requires(condition = ManagementProcessCondition.class)
    public LoginService loginService(ModuleRegistry modules, GameAccountRepository accounts,
                                      PlayerCharacterRepository characters, InventoryItemRepository inventories) throws Exception {
        return modules.register("login-logic", "org.gms.logic.login.", List.of(LoginService.class),
                new HostServices(Map.of(GameAccountRepository.class, accounts, PlayerCharacterRepository.class, characters,
                        InventoryItemRepository.class, inventories))).service(LoginService.class);
    }

    @Bean @Context @Requires(condition = ManagementProcessCondition.class)
    public ManagementLogic managementLogic(ModuleRegistry modules, GameAccountRepository accounts,
            AccountDeletionRepository deletions, PlayerCharacterRepository characters, AdminService admin) throws Exception {
        return new ManagementLogic(modules.register("admin-logic", "org.gms.logic.admin.",
                List.of(AccountOperations.class, AdminAccessPolicy.class), new HostServices(Map.of(
                        GameAccountRepository.class, accounts, AccountDeletionRepository.class, deletions,
                        PlayerCharacterRepository.class, characters, AdminService.class, admin))));
    }

    public record ManagementLogic(ModuleRuntime runtime) { }
    @Bean @Singleton @Requires(condition = ManagementProcessCondition.class)
    public AccountOperations accountOperations(ManagementLogic logic) { return logic.runtime().service(AccountOperations.class); }
    @Bean @Singleton @Requires(condition = ManagementProcessCondition.class)
    public AdminAccessPolicy adminAccessPolicy(ManagementLogic logic) { return logic.runtime().service(AdminAccessPolicy.class); }

    @Bean @Context @Requires(condition = ManagementProcessCondition.class)
    public CharacterQueries characterQueries(ModuleRegistry modules, GameAccountRepository accounts,
            PlayerCharacterRepository characters, InventoryItemRepository inventories, QuestRepository quests,
            SkillRepository skills, BuddyListRepository buddies) throws Exception {
        return modules.register("query-logic", "org.gms.logic.query.", List.of(CharacterQueries.class),
                new HostServices(Map.of(GameAccountRepository.class, accounts, PlayerCharacterRepository.class, characters,
                        InventoryItemRepository.class, inventories, QuestRepository.class, quests,
                        SkillRepository.class, skills, BuddyListRepository.class, buddies))).service(CharacterQueries.class);
    }

    @Bean @Context @Requires(condition = ManagementProcessCondition.class)
    public ChannelSelectionPolicy channelSelectionPolicy(ModuleRegistry modules) throws Exception {
        return modules.register("coordinator-logic", "org.gms.logic.coordinator.", List.of(ChannelSelectionPolicy.class),
                new HostServices(Map.of())).service(ChannelSelectionPolicy.class);
    }
}
