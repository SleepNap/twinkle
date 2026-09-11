package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.gms.httpapi.admin.AdminAccessPolicy;
import org.gms.httpapi.application.admin.AccountOperations;
import org.gms.httpapi.application.query.CharacterQueries;
import org.gms.login.LoginService;
import org.gms.module.ModuleRegistry;
import org.gms.service.intercoord.ChannelSelectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 完整管理进程装配使用临时 SQLite 与本机临时端口，业务实现必须来自外置制品。 */
public class LogicModuleAssemblyTest {
    @TempDir public Path directory;

    @Test public void managementContextResolvesAllExternalLogicProxies() throws Exception {
        int internalPort;
        try (var reservation = new ServerSocket(0)) { internalPort = reservation.getLocalPort(); }
        Map<String, Object> config = new HashMap<>();
        config.put("twinkle.role", "coordinator");
        config.put("twinkle.profile", "split-channel");
        config.put("twinkle.db.url", "jdbc:sqlite:" + directory.resolve("isolated.db"));
        config.put("twinkle.db.user", ""); config.put("twinkle.db.password", "");
        config.put("twinkle.db.dialect", "sqlite");
        config.put("twinkle.logic.path", Path.of("../target/logic").toAbsolutePath().normalize().toString());
        config.put("twinkle.coordinator.host", "127.0.0.1");
        config.put("twinkle.coordinator.port", internalPort);
        config.put("twinkle.net.login.port", 0);
        config.put("twinkle.admin.shutdown.exit", false);
        config.put("twinkle.admin.restart.exit", false);
        config.put("twinkle.admin.shutdown.timeout", 1000);
        config.put("micronaut.server.port", -1);
        try (var context = ApplicationContext.builder().properties(config).start()) {
            assertThat(context.getBean(ModuleRegistry.class).versions()).containsOnlyKeys(
                    "login-logic", "admin-logic", "query-logic", "coordinator-logic");
            for (Class<?> type : new Class<?>[]{LoginService.class, AccountOperations.class,
                    CharacterQueries.class, AdminAccessPolicy.class, ChannelSelectionPolicy.class}) {
                assertThat(java.lang.reflect.Proxy.isProxyClass(context.getBean(type).getClass())).isTrue();
            }
            assertThat(context.getBean(LoginService.class).authenticate("missing-test-user", "unused").errorCode()).isEqualTo(5);
            assertThat(context.getBean(CharacterQueries.class).detail(1, 1).code()).isEqualTo(404);
        }
    }
}
