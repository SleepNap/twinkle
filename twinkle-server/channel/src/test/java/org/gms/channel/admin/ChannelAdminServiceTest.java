package org.gms.channel.admin;

import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.script.ScriptEngine;
import org.gms.domain.script.ScriptManager;
import org.gms.domain.script.ScriptRepository;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.packet.PacketSession;
import org.gms.service.admin.AdminService;
import org.gms.tick.GameTickLoop;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-1 第②路集成测试：管理侧经 {@link AdminService} 踢下线（架构 M3-1 数据三路第②路）。
 *
 * <p>M5 扩展：运维操作（脚本重载 / 重启请求 / 重启阶段）经 service 接口委托频道侧组件，
 * 管理侧不 import ScriptManager/RestartService 具体类。重启请求异步执行——用 mock restart
 * runnable（不真退出），断言后台线程触发重启编排且阶段推进。
 */
class ChannelAdminServiceTest {

    /** 构造一个完整装配的 ChannelAdminService（临时 SQLite + 手动频道组件，mock restart 不真退出）。 */
    private static ChannelAdminService buildAdmin() throws Exception {
        return buildAdmin(new PlayerStorage());
    }

    private static ChannelAdminService buildAdmin(PlayerStorage players) throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-chadmin").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        com.mybatisflex.core.MybatisFlexBootstrap flex = new com.mybatisflex.core.MybatisFlexBootstrap();
        flex.setEnvironmentId("chadmin-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        DefaultVersionGate versionGate = new DefaultVersionGate();
        CharacterLoader loader = new CharacterLoader(versionGate);
        FlexCharacterRepository repo = new FlexCharacterRepository(characterMapper);
        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repo, loader, players);
        GameTickLoop tickLoop = new GameTickLoop(5);
        EntityReloadCoordinator coordinator = new EntityReloadCoordinator();
        EntityReloadService reloadService = new EntityReloadService(coordinator, versionGate);
        RestartCoordinator restartCoordinator = new RestartCoordinator();
        RestartService restartService = new RestartService(restartCoordinator, tickLoop, reloadService, saveQueue);

        String scriptDir = Files.createTempDirectory("twinkle-chadmin-script").toString();
        ScriptManager scriptManager = new ScriptManager(new ScriptEngine(), new ScriptRepository(Path.of(scriptDir)));

        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        return new ChannelAdminService(players, sessions, 1, scriptManager, restartService, restartCoordinator,
                () -> { /* mock restart：测试不真退出 */ });
    }

    @Test
    void inventorySnapshotProjectsOnlineMemoryStateWithoutLeakingDomainObjects() throws Exception {
        PlayerStorage players = new PlayerStorage();
        Character character = new Character(1L);
        character.setId(42L);
        character.setName("Hero");
        Equip equip = new Equip(1_040_002);
        equip.setPosition((short) -5);
        equip.setStr((short) 12);
        equip.setWatk((short) 3);
        character.getInventory(InventoryType.EQUIP).putAtSlot((short) -5, equip);
        PetItem pet = new PetItem(5_000_000, 9001);
        pet.setPosition((short) 2);
        pet.setPetName("小黑");
        pet.setCloseness((short) 3456);
        character.getInventory(InventoryType.CASH).putAtSlot((short) 2, pet);
        character.markDirty();
        players.add(character);
        ChannelAdminService admin = buildAdmin(players);

        AdminService.PlayerInventory snapshot = admin.inventorySnapshot(42L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.characterId()).isEqualTo(42L);
        assertThat(snapshot.name()).isEqualTo("Hero");
        assertThat(snapshot.stateVersion()).isEqualTo(character.dirtyVersion());
        assertThat(snapshot.items()).hasSize(2);
        assertThat(snapshot.items().getFirst().itemType()).isEqualTo("equip");
        assertThat(snapshot.items().getFirst().equip().strength()).isEqualTo(12);
        assertThat(snapshot.items().getFirst().equip().weaponAttack()).isEqualTo(3);
        assertThat(snapshot.items().getLast().itemType()).isEqualTo("pet");
        assertThat(snapshot.items().getLast().petId()).isEqualTo(9001);
        assertThat(snapshot.items().getLast().pet().name()).isEqualTo("小黑");
        assertThat(snapshot.items().getLast().pet().closeness()).isEqualTo(3456);
        assertThat(admin.inventorySnapshot(999L)).isNull();
    }

    @Test
    void kickClosesOnlineSession() throws Exception {
        // 用独立的最小装配：空数据层 + 空脚本目录，注册一个假会话
        ChannelAdminService admin = buildAdmin();
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        // 注入会话：用反射不行，直接经构造后的 admin 无法拿 registry——改为测试 buildAdmin 后单独装配
        // 简化：直接在当前 registry 注册并复用 admin（buildAdmin 内部 registry 拿不到，此处重建 admin 传 registry）
        admin = buildAdminWithSessions(sessions);

        AtomicBoolean closed = new AtomicBoolean(false);
        PacketSession fakeSession = new PacketSession() {
            @Override
            public void send(org.gms.net.packet.OutPacket packet) {
            }

            @Override
            public void close(String reason) {
                closed.set(true);
            }

            @Override
            public org.gms.net.packet.SessionStage stage() {
                return org.gms.net.packet.SessionStage.IN_GAME;
            }

            @Override
            public void transition(org.gms.net.packet.SessionStage stage) {
            }

            @Override
            public <T> T getAttr(String key) {
                return null;
            }

            @Override
            public void setAttr(String key, Object value) {
            }

            @Override
            public long sessionId() {
                return 4242;
            }
        };
        sessions.claim(42L, fakeSession);

        assertThat(admin.kick(42L)).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void kickOfflineCharacterReturnsFalse() throws Exception {
        ChannelAdminService admin = buildAdmin();
        assertThat(admin.kick(999L)).isFalse();
    }

    @Test
    void reloadScriptsDelegatesToScriptManager() throws Exception {
        ChannelAdminService admin = buildAdmin();
        // 空脚本目录：无变化脚本（不抛异常）
        assertThat(admin.reloadScripts()).isZero();
    }

    @Test
    void requestRestartRunsAsyncAndAdvancesPhase() throws Exception {
        ChannelAdminService admin = buildAdmin();
        assertThat(admin.restartPhase()).isEqualTo(RestartCoordinator.Phase.RUNNING);

        admin.requestRestart();

        // 后台守护线程异步执行，轮询等待推进到非 RUNNING 阶段（空玩家 + 空队列的编排会快速推进）
        long deadline = System.currentTimeMillis() + 5000;
        RestartCoordinator.Phase seen = RestartCoordinator.Phase.RUNNING;
        while (System.currentTimeMillis() < deadline && seen == RestartCoordinator.Phase.RUNNING) {
            seen = admin.restartPhase();
            Thread.sleep(10);
        }
        assertThat(seen).isNotEqualTo(RestartCoordinator.Phase.RUNNING);
    }

    /** buildAdmin 变体：复用给定会话注册表（kick 测试需要预注册会话）。 */
    private static ChannelAdminService buildAdminWithSessions(PlayerSessionRegistry sessions) throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-chadmin2").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        com.mybatisflex.core.MybatisFlexBootstrap flex = new com.mybatisflex.core.MybatisFlexBootstrap();
        flex.setEnvironmentId("chadmin2-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        DefaultVersionGate versionGate = new DefaultVersionGate();
        CharacterLoader loader = new CharacterLoader(versionGate);
        PlayerStorage players = new PlayerStorage();
        FlexCharacterRepository repo = new FlexCharacterRepository(characterMapper);
        CharacterSaveQueue saveQueue = new CharacterSaveQueue(repo, loader, players);
        GameTickLoop tickLoop = new GameTickLoop(5);
        EntityReloadCoordinator coordinator = new EntityReloadCoordinator();
        EntityReloadService reloadService = new EntityReloadService(coordinator, versionGate);
        RestartCoordinator restartCoordinator = new RestartCoordinator();
        RestartService restartService = new RestartService(restartCoordinator, tickLoop, reloadService, saveQueue);

        String scriptDir = Files.createTempDirectory("twinkle-chadmin2-script").toString();
        ScriptManager scriptManager = new ScriptManager(new ScriptEngine(), new ScriptRepository(Path.of(scriptDir)));

        return new ChannelAdminService(players, sessions, 1, scriptManager, restartService, restartCoordinator,
                () -> { /* mock restart：测试不真退出 */ });
    }
}
