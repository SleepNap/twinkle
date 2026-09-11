package org.gms.bootstrap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** 可执行的模块依赖红线；新代码一旦反向穿透，verify 会直接失败。 */
class ArchitectureRulesTest {

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.gms");

    @Test
    void hostMustNeverReferenceReloadableImplementation() {
        noClasses().that().resideOutsideOfPackage("org.gms.logic..")
                .should().dependOnClassesThat().resideInAPackage("org.gms.logic..")
                .check(production);
        noClasses().that().resideInAPackage("org.gms.logic..")
                .should().beAnnotatedWith("jakarta.inject.Singleton").check(production);
        noClasses().that().resideInAPackage("org.gms.logic..")
                .should().beAnnotatedWith("io.micronaut.http.annotation.Controller").check(production);
        noClasses().that().resideInAnyPackage("org.gms.logic.login..", "org.gms.logic.admin..",
                        "org.gms.logic.query..", "org.gms.logic.coordinator..")
                .should().dependOnClassesThat().resideInAPackage("org.gms.domain.game..").check(production);
    }

    @Test
    void coreMustNotDependOnFeatureOrAdapterModules() {
        noClasses().that().resideInAPackage("org.gms..")
                .and().resideOutsideOfPackages("org.gms.bootstrap..")
                .should().dependOnClassesThat().resideInAPackage("org.gms.bootstrap..")
                .check(production);

        noClasses().that().resideInAPackage("org.gms.event..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.gms.persistence..", "org.gms.channel..", "org.gms.httpapi..")
                .check(production);
    }

    @Test
    void gameDomainMustRemainFrameworkAndPersistenceFree() {
        noClasses().that().resideInAPackage("org.gms.domain.game..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.gms.persistence..", "org.gms.httpapi..", "io.micronaut..",
                        "io.netty..", "org.springframework..")
                .check(production);
    }

    @Test
    void protocolModuleMustNotDependOnNettyTransport() {
        noClasses().that().resideInAnyPackage("org.gms.net.packet..", "org.gms.net.encryption..")
                .should().dependOnClassesThat().resideInAPackage("org.gms.net.netty..")
                .check(production);

        noClasses().that().resideInAPackage("org.gms.net.netty..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.gms.coordinator..", "org.gms.login..", "org.gms.httpapi..",
                        "org.gms.channel..", "org.gms.domain..", "org.gms.wz..",
                        "org.gms.persistence..")
                .check(production);
    }

    @Test
    void managementModulesMustNotReachIntoGameDomain() {
        noClasses().that().resideInAnyPackage(
                        "org.gms.login..", "org.gms.coordinator..", "org.gms.httpapi..")
                .should().dependOnClassesThat().resideInAPackage("org.gms.domain.game..")
                .check(production);
    }

    @Test
    void pluginApiMustStayIndependentFromServerImplementation() {
        noClasses().that().resideInAPackage("org.gms.plugin..")
                .and().resideOutsideOfPackage("org.gms.plugin.runtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.gms.persistence..", "org.gms.channel..", "org.gms.bootstrap..",
                        "io.micronaut..", "io.netty..", "org.apache.logging..")
                .check(production);
    }

    @Test
    void gameMemoryObjectsMustNotBecomeDependencyInjectionSingletons() {
        noClasses().that().resideInAPackage("org.gms.domain.game..")
                .should().beAnnotatedWith("jakarta.inject.Singleton")
                .check(production);
        noClasses().that().resideInAPackage("org.gms.domain.game..")
                .should().beAnnotatedWith("io.micronaut.context.annotation.Bean")
                .check(production);
        noClasses().that().resideInAPackage("org.gms.domain.game..")
                .should().beAnnotatedWith("io.micronaut.context.annotation.Context")
                .check(production);
    }

    @Test
    void productionFeaturePackagesMustRemainFreeOfCycles() {
        slices().matching("org.gms.(*)..")
                .should().beFreeOfCycles()
                .check(production);
    }
}
