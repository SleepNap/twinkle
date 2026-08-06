package org.gms.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构测试：依赖单向无环 + 可替换层纪律（架构第三节 / 红线 11、12）。
 *
 * <p>放在 bootstrap 模块（依赖全部模块），测试运行时 classpath 覆盖所有 org.gms 类。
 * 扫描 classpath 下所有 {@code org.gms} 类。四条硬规则：
 * <ol>
 *   <li><b>管理侧不得依赖 domain-game</b>：coordinator/login/admin/http-api/ai 编译期禁止 import
 *       {@code org.gms.domain.game}（架构 4.1：管理侧禁止直踩游戏内存）。</li>
 *   <li><b>domain-game 不得依赖管理侧</b>：数据模型单向依赖核心底座，不反向。</li>
 *   <li><b>可替换层不得引用稳定层具体类</b>：可重载逻辑必须经接口访问稳定层（红线 11，防换 classloader CCE）。</li>
 *   <li><b>管理侧 HTTP/AI 不得依赖协议栈</b>：不碰 net-netty / net-packet（架构：管理侧经 application service 交互）。</li>
 * </ol>
 */
class ArchitectureDependencyTest {

    private static final JavaClasses ALL_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            // 默认 ImportOption 会排除 jar（DO_NOT_INCLUDE_ARCHIVES 排掉所有压缩包）。
            // 本架构测试依赖跨模块（管理侧/游戏域类在各自 jar 里），需保留 jar 里的 org.gms 类参与扫描。
            // 只排除 JDK 模块（jrt: scheme）与 classpath 外的 jar。
            .withImportOption(new ImportOption() {
                @Override
                public boolean includes(com.tngtech.archunit.core.importer.Location location) {
                    return !"jrt".equals(location.asURI().getScheme());
                }
            })
            .importPackages("org.gms");

    private static final String[] MANAGEMENT_SIDE_PACKAGES = {
            "org.gms.coordinator..", "org.gms.login..", "org.gms.admin..",
            "org.gms.httpapi..", "org.gms.ai.."
    };

    // ---- 规则 1：管理侧不得依赖 domain-game ----
    @Test
    void managementSideMustNotDependOnDomainGame() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(MANAGEMENT_SIDE_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage("org.gms.domain.game..");
        rule.check(ALL_CLASSES);
    }

    // ---- 规则 2：domain-game 不得依赖管理侧 ----
    @Test
    void domainGameMustNotDependOnManagementSide() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("org.gms.domain.game..")
                .should().dependOnClassesThat().resideInAnyPackage(MANAGEMENT_SIDE_PACKAGES);
        rule.check(ALL_CLASSES);
    }

    // ---- 规则 3：可替换层不得引用稳定层具体类 ----
    // 稳定层 = 数据模型实体（org.gms.domain.game.. / org.gms.data.entity..）；可替换层在 org.gms.replaceable..
    // M0 阶段尚无实际可替换层代码，规则是前瞻性的——allowEmptyShould(true) 允许空集（当前无该类即通过，
    // 一旦 M2/M4 引入 replaceable 包，规则立即生效）。
    @Test
    void replaceableLayerMustNotDependOnStableConcreteClasses() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("org.gms.replaceable..", "org.gms.plugins..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.gms.domain.game..", "org.gms.data.entity..")
                .allowEmptyShould(true); // M0 无该类 → 空集通过
        rule.check(ALL_CLASSES);
    }

    // ---- 规则 4：管理侧 HTTP/AI 不得依赖协议栈 ----
    @Test
    void managementSideMustNotDependOnNetStack() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(MANAGEMENT_SIDE_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage("org.gms.net.packet..", "org.gms.net.netty..");
        rule.check(ALL_CLASSES);
    }
}
