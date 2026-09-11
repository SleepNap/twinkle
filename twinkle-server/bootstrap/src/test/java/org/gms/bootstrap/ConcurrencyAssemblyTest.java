package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.plugin.runtime.PluginManager;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 仅使用临时 SQLite、独立资源和本机临时端口，验证真实双频道装配与关闭。 */
public class ConcurrencyAssemblyTest {
    @Test public void twoChannelsUseSharedBoundedPersistenceAndConsistentWz(@TempDir Path root) throws Exception {
        int firstPort, secondPort;
        try (var first = new ServerSocket(0); var second = new ServerSocket(0)) {
            firstPort = first.getLocalPort(); secondPort = second.getLocalPort();
        }
        Path wz = Files.createDirectories(root.resolve("wz"));
        Path map = Files.createDirectories(wz.resolve("Map.wz/Map/Map1")).resolve("100000000.img.xml");
        Files.writeString(map, xml(0));
        Map<String, Object> config = new HashMap<>();
        config.put("twinkle.profile", "single");
        config.put("twinkle.db.url", "jdbc:sqlite:" + root.resolve("isolated.db"));
        config.put("twinkle.db.user", ""); config.put("twinkle.db.password", ""); config.put("twinkle.db.dialect", "sqlite");
        config.put("twinkle.logic.path", Path.of("../target/logic").toAbsolutePath().normalize().toString());
        config.put("twinkle.wz.path", wz.toString());
        config.put("twinkle.script.path", Files.createDirectories(root.resolve("scripts")).toString());
        config.put("twinkle.plugin.path", Files.createDirectories(root.resolve("plugins")).toString());
        config.put("twinkle.worker.channels", "1:" + firstPort + ",2:" + secondPort);
        config.put("twinkle.persistence.save-capacity", 3);
        config.put("twinkle.net.login.port", 0);
        config.put("twinkle.admin.shutdown.exit", false); config.put("twinkle.admin.restart.exit", false);
        config.put("twinkle.admin.shutdown.timeout", 30000); config.put("micronaut.server.port", -1);
        try (var context = ApplicationContext.builder().properties(config).start()) {
            var worker = context.getBean(ChannelWorker.class);
            assertThat(worker.channelIds()).containsExactly(1, 2);
            assertThat(context.getBean(CharacterSaveQueue.class).status().capacity()).isEqualTo(3);
            assertThat(context.getBean(PluginManager.class).loadedPlugins()).isEmpty();
            var a = worker.runtime(1).maps().getMap(100000000);
            var b = worker.runtime(2).maps().getMap(100000000);
            assertThat(a).isNotSameAs(b);
            Files.writeString(map, xml(1));
            var report = context.getBean(AdminService.class).reloadWz();
            assertThat(report.failures()).isEmpty();
            assertThat(report.versions()).containsEntry("channel-maps:1", 2L).containsEntry("channel-maps:2", 2L);
            assertThat(worker.runtime(1).sessions().execution().call(a::isTown)).isTrue();
            assertThat(worker.runtime(2).sessions().execution().call(b::isTown)).isTrue();
        }
    }
    private String xml(int town) { return "<imgdir name=\"100000000.img\"><imgdir name=\"info\"><int name=\"town\" value=\"" + town + "\"/></imgdir></imgdir>"; }
}
