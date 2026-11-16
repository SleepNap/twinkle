package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.script.ScriptEngine;
import org.gms.domain.script.ScriptManager;
import org.gms.domain.script.ScriptRepository;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 传送门脚本（{@code scripts/portal/<scriptName>.js}）与地图进入脚本
 * （{@code scripts/field/<onUserEnter|onFirstUserEnter>.js}）的自动回归。
 *
 * <p>被测对象是 {@link MapTransitionService}（服务端选图与落点）、{@link PortalScriptHost}、
 * {@link PlayerMapTransitionHandler} 与 {@link FieldScriptHost}。脚本不用桩：每个用例都在
 * {@link TempDir} 下真写 {@code portal/*.js}、{@code field/*.js}，用真实 {@link ScriptManager}
 * 加 GraalVM {@link ScriptEngine} 执行，从而把「脚本 → 宿主 → 换图 / 广播」整条链路走一遍。
 *
 * <p><b>依赖可空性（读码结论，不是假设）</b>：{@link MapTransitionService} 的
 * {@code monsters}/{@code leases} 传 {@code null} 可用，换图仍会完整走完 {@code transfer}：
 *
 * <ul>
 *   <li>{@code leaveMap} 的租约释放分支是 {@code if (leases != null && generation != null)}
 *       （MapTransitionService:207）；</li>
 *   <li>{@code populate} 的刷怪与 {@code leases.onClaim} 整段包在 {@code if (monsters != null)}
 *       内（MapTransitionService:230）；</li>
 *   <li>仓库既有用例同样这样构造并走通了换图：{@code ReviveGameplayTest#transitions}、
 *       {@code MapAndDropGameplayTest#portalUsesServerDestinationAndRejectsRemoteOrForgedRequests}。</li>
 * </ul>
 *
 * <p>代价只有一个：换图时不会收刷怪 / 控制权包，所以落点断言一律看 {@code SET_FIELD} 与角色坐标
 * （{@code spawnPoint}/x/y），不看 {@code SPAWN_MONSTER}。反过来说，本类没有任何用例因为依赖
 * 不可构造而被迫放弃。
 */
public class ScriptHostGameplayTest {

    /** 起点地图（脚本门所在图）。 */
    private static final int ORIGIN = 100;
    /** 主目标地图：用 v83 真实地图 id 占位，脚本里写死同一个数值。 */
    private static final int TARGET = 100_000_000;
    /** 备用目标地图：用于区分「第一次 warp」和「第二次 warp / 另一个脚本」。 */
    private static final int ALTERNATE = 200;

    /**
     * 每个用例独立的脚本根目录。
     *
     * <p>用生命周期方法参数注入而不是直接注字段：{@code @TempDir} 不允许私有字段，
     * 而本项目红线 12 禁止包私有字段，参数注入两不冲突。
     */
    private Path scriptRoot;

    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();

    private final ScriptEngine engine = new ScriptEngine();

    @BeforeEach
    public void setUp(@TempDir Path root) {
        scriptRoot = root;
    }

    @AfterEach
    public void tearDown() {
        engine.close();
    }

    /** 脚本门 {@code portal.warp(mapId)}：角色真的换到目标地图，落点由服务端挑。 */
    @Test
    public void portalScriptWarpTransfersCharacter() throws IOException {
        writeScript("portal/warp_to_100000000", "portal.warp(100000000);\n");
        MapleMap origin = map(ORIGIN);
        origin.putPortal(scriptPortal(1, "warp", "warp_to_100000000"));
        MapleMap target = map(TARGET);
        target.putPortal(portal(6, "sp", 30, 40));
        GameplayTestSession session = joined(1, origin);

        boolean used = transitions(Map.of(ORIGIN, origin, TARGET, target), scripts())
                .usePortal(session, "warp", -1);

        assertThat(used).isTrue();
        assertThat(session.character.getMap()).isEqualTo(TARGET);
        assertThat(session.character.getMapObject()).isSameAs(target);
        // 脚本只写 warp(mapId)（落点参数缺省）：warpTo 退化为目标地图第一个具名传送点。
        assertThat(session.character.getSpawnPoint()).isEqualTo(6);
        assertThat(session.character.getX()).isEqualTo(30);
        assertThat(session.character.getY()).isEqualTo(40);
        assertThat(origin.characters()).isEmpty();
        assertThat(target.characters()).containsExactly(session.character);
        assertThat(opcodes(session)).contains(SendOpcode.SET_FIELD.getValue());
    }

    /** 脚本抛异常：只拒绝本次传送，异常不外泄，角色留在原地。 */
    @Test
    public void portalScriptErrorRejectsTransfer() throws IOException {
        writeScript("portal/boom", "throw new Error(\"boom\");\n");
        MapleMap origin = map(ORIGIN);
        origin.putPortal(scriptPortal(1, "boom", "boom"));
        MapleMap target = map(TARGET);
        target.putPortal(portal(6, "sp", 30, 40));
        GameplayTestSession session = joined(1, origin);
        MapTransitionService service = transitions(Map.of(ORIGIN, origin, TARGET, target), scripts());

        assertThatCode(() -> assertThat(service.usePortal(session, "boom", -1)).isFalse())
                .doesNotThrowAnyException();

        assertThat(session.character.getMap()).isEqualTo(ORIGIN);
        assertThat(origin.characters()).containsExactly(session.character);
        assertThat(target.characters()).isEmpty();
        // 异常路径连第一次换图包都不会产生，客户端不会看到半截进图。
        assertThat(opcodes(session)).doesNotContain(SendOpcode.SET_FIELD.getValue());
    }

    /** 脚本没主动传送、但门自带普通目标地图：回退成普通传送。 */
    @Test
    public void portalScriptWithoutWarpFallsBackToPortalTarget() throws IOException {
        writeScript("portal/sound_only", "portal.playPortalSound();\n");
        MapleMap origin = map(ORIGIN);
        Portal gate = scriptPortal(1, "sound", "sound_only");
        gate.setTargetMapId(ALTERNATE);
        gate.setTargetPortalName("sp");
        origin.putPortal(gate);
        MapleMap target = map(ALTERNATE);
        target.putPortal(portal(2, "sp", 5, 6));
        GameplayTestSession session = joined(1, origin);

        boolean used = transitions(Map.of(ORIGIN, origin, ALTERNATE, target), scripts())
                .usePortal(session, "sound", -1);

        assertThat(used).isTrue();
        assertThat(session.character.getMap()).isEqualTo(ALTERNATE);
        assertThat(session.character.getSpawnPoint()).isEqualTo(2);
        assertThat(session.character.getX()).isEqualTo(5);
        assertThat(target.characters()).containsExactly(session.character);
    }

    /** 脚本文件不存在（key 命中不了）：返回 false 且不换图。 */
    @Test
    public void portalScriptMissingKeepsPlayerInPlace() throws IOException {
        // 只写同名脚本以外的文件，确保失败原因是 key 不存在而不是目录为空。
        writeScript("portal/unrelated", "portal.warp(200);\n");
        MapleMap origin = map(ORIGIN);
        Portal gate = scriptPortal(1, "ghost", "ghost_gate_missing");
        gate.setTargetMapId(0);
        origin.putPortal(gate);
        MapleMap target = map(TARGET);
        target.putPortal(portal(6, "sp", 30, 40));
        GameplayTestSession session = joined(1, origin);

        boolean used = transitions(Map.of(ORIGIN, origin, TARGET, target), scripts())
                .usePortal(session, "ghost", -1);

        assertThat(used).isFalse();
        assertThat(session.character.getMap()).isEqualTo(ORIGIN);
        assertThat(origin.characters()).containsExactly(session.character);
        assertThat(opcodes(session)).doesNotContain(SendOpcode.SET_FIELD.getValue());
    }

    /** 脚本连写两次 warp：只第一次生效，第二次既不改图也不再发进图包。 */
    @Test
    public void portalWarpIgnoresSecondCall() throws IOException {
        writeScript("portal/double_warp",
                "portal.warp(100000000);\nportal.warp(200);\n");
        MapleMap origin = map(ORIGIN);
        origin.putPortal(scriptPortal(1, "warp", "double_warp"));
        MapleMap target = map(TARGET);
        target.putPortal(portal(6, "sp", 30, 40));
        MapleMap alternate = map(ALTERNATE);
        alternate.putPortal(portal(2, "sp", 5, 6));
        GameplayTestSession session = joined(1, origin);

        boolean used = transitions(Map.of(ORIGIN, origin, TARGET, target, ALTERNATE, alternate), scripts())
                .usePortal(session, "warp", -1);

        assertThat(used).isTrue();
        assertThat(session.character.getMap()).isEqualTo(TARGET);
        assertThat(session.character.getMapObject()).isSameAs(target);
        assertThat(countOpcode(session, SendOpcode.SET_FIELD)).isEqualTo(1);
        assertThat(alternate.characters()).isEmpty();
        assertThat(target.characters()).containsExactly(session.character);
    }

    /** 地图进入脚本 {@code em.broadcastMessage}：同图所有会话都收到 type 4 公告。 */
    @Test
    public void fieldScriptBroadcastsToSameMapSessions() throws IOException {
        writeScript("field/enter", "em.broadcastMessage(\"hi\");\n");
        MapleMap map = map(ORIGIN);
        map.setOnUserEnter("enter");
        GameplayTestSession first = joined(1, map);
        GameplayTestSession second = joined(2, map);
        PlayerMapTransitionHandler handler = new PlayerMapTransitionHandler(scripts(), sessions);

        handler.handle(first, new ByteArrayInPacket(new byte[0]));

        assertThat(serverMessages(first)).containsExactly("hi");
        // 第二个玩家没发进图完成包，也必须收到同图广播。
        assertThat(serverMessages(second)).containsExactly("hi");
    }

    /** {@code onFirstUserEnter}：同一地图运行实例只执行一次。 */
    @Test
    public void fieldScriptRunsOnlyOncePerMapInstance() throws IOException {
        writeScript("field/once", "em.broadcastMessage(\"once\");\n");
        MapleMap map = map(ORIGIN);
        map.setOnFirstUserEnter("once");
        GameplayTestSession first = joined(1, map);
        GameplayTestSession second = joined(2, map);
        PlayerMapTransitionHandler handler = new PlayerMapTransitionHandler(scripts(), sessions);

        handler.handle(first, new ByteArrayInPacket(new byte[0]));
        handler.handle(second, new ByteArrayInPacket(new byte[0]));

        // 用广播次数当计数器：脚本若跑两次，每个会话都会拿到两条公告。
        assertThat(serverMessages(first)).containsExactly("once");
        assertThat(serverMessages(second)).containsExactly("once");
    }

    /** 进入脚本抛异常：进图本身不受影响，{@code mapTransition} 照旧被清掉。 */
    @Test
    public void fieldScriptErrorDoesNotBlockEntry() throws IOException {
        writeScript("field/boom", "throw new Error(\"boom\");\n");
        MapleMap map = map(ORIGIN);
        map.setOnUserEnter("boom");
        GameplayTestSession session = joined(1, map);
        session.setAttr("mapTransition", true);
        PlayerMapTransitionHandler handler = new PlayerMapTransitionHandler(scripts(), sessions);

        assertThatCode(() -> handler.handle(session, new ByteArrayInPacket(new byte[0])))
                .doesNotThrowAnyException();

        // 异常只记日志：客户端已经进图，清理与后续移动资格不能因此卡住。
        assertThat((Object) session.getAttr("mapTransition")).isNull();
        assertThat(session.character.getMap()).isEqualTo(ORIGIN);
        assertThat(session.sent).isEmpty();
    }

    /** 地图字段指向的脚本不存在：静默跳过，不抛异常也不发包。 */
    @Test
    public void fieldScriptMissingIsSilent() {
        MapleMap map = map(ORIGIN);
        map.setOnUserEnter("not_here");
        GameplayTestSession session = joined(1, map);
        PlayerMapTransitionHandler handler = new PlayerMapTransitionHandler(scripts(), sessions);

        assertThatCode(() -> handler.handle(session, new ByteArrayInPacket(new byte[0])))
                .doesNotThrowAnyException();

        assertThat(session.sent).isEmpty();
        assertThat((Object) session.getAttr("mapTransition")).isNull();
    }

    /**
     * 脚本 key 用 {@code portal/<scriptName>}：与门名同名的脚本不得被执行。
     *
     * <p>顺带覆盖 {@code portal.warp(mapId, portalName)} 这条双参重载（脚本点名落点），
     * 与 {@link #portalScriptWarpTransfersCharacter} 里落点缺省的默认分支互补。
     */
    @Test
    public void portalScriptNameFromPortalDrivesKey() throws IOException {
        // "gate" 是门名、也是同目录下另一份脚本的名字：它一旦被执行就会跑到 ALTERNATE。
        writeScript("portal/gate", "portal.warp(200);\n");
        writeScript("portal/blue", "portal.warp(100000000, \"sp\");\n");
        MapleMap origin = map(ORIGIN);
        origin.putPortal(scriptPortal(1, "gate", "blue"));
        MapleMap target = map(TARGET);
        target.putPortal(portal(6, "sp", 30, 40));
        MapleMap alternate = map(ALTERNATE);
        alternate.putPortal(portal(2, "sp", 5, 6));
        GameplayTestSession session = joined(1, origin);

        boolean used = transitions(Map.of(ORIGIN, origin, TARGET, target, ALTERNATE, alternate), scripts())
                .usePortal(session, "gate", -1);

        assertThat(used).isTrue();
        assertThat(session.character.getMap()).isEqualTo(TARGET);
        assertThat(alternate.characters()).isEmpty();
    }

    // ---------------- 夹具 ----------------

    /** 在临时脚本根目录写 {@code <relative>.js}：key 就是相对路径去掉扩展名。 */
    private void writeScript(String relative, String content) throws IOException {
        Path file = scriptRoot.resolve(relative + ".js");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** 真实脚本管理器：构造时扫描已写好的脚本目录。 */
    private ScriptManager scripts() {
        return new ScriptManager(engine, new ScriptRepository(scriptRoot));
    }

    /**
     * 换图服务：{@code monsters}/{@code leases} 传 null（理由见类注释）。
     *
     * <p>执行上下文挂 {@link PlayerSessionRegistry}，与生产装配同形；它没有 {@code GameExecution}
     * 时用自身监视器串行，不起线程。
     */
    private MapTransitionService transitions(Map<Integer, MapleMap> maps, ScriptManager scripts) {
        return new MapTransitionService(maps::get, null, null, 1, sessions, scripts);
    }

    /** 进图会话：默认站在 (0,0)，因此门坐标给 (0,0) 即满足 150 距离判定。 */
    private GameplayTestSession joined(long id, MapleMap map) {
        GameplayTestSession session = new GameplayTestSession(id, map);
        sessions.claim(id, session);
        return session;
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap();
        map.setMapId(id);
        return map;
    }

    /** 脚本门：坐标 (0,0)，{@code scriptName} 决定执行哪个 {@code portal/*.js}。 */
    private static Portal scriptPortal(int id, String name, String scriptName) {
        Portal portal = new Portal(id, name, PortalType.SCRIPT, 0, 0);
        portal.setScript(true);
        portal.setScriptName(scriptName);
        return portal;
    }

    /** 目标地图落点：id 同时是出生点编号（v83 上限 255）。 */
    private static Portal portal(int id, String name, int x, int y) {
        return new Portal(id, name, PortalType.MOVE, x, y);
    }

    // ---------------- 出包读取 ----------------

    private static int opcodeOf(OutPacket packet) {
        return new ByteArrayInPacket(packet.getBytes()).readUnsignedShort();
    }

    private static List<Integer> opcodes(GameplayTestSession session) {
        return session.sent.stream().map(ScriptHostGameplayTest::opcodeOf).toList();
    }

    private static long countOpcode(GameplayTestSession session, SendOpcode opcode) {
        return session.sent.stream().filter(packet -> opcodeOf(packet) == opcode.getValue()).count();
    }

    /** 会话收到的服务器公告正文（顺带断言 opcode 与 type 4，且包体没有多余尾部）。 */
    private static List<String> serverMessages(GameplayTestSession session) {
        return session.sent.stream()
                .filter(packet -> opcodeOf(packet) == SendOpcode.SERVERMESSAGE.getValue())
                .map(packet -> {
                    ByteArrayInPacket wire = new ByteArrayInPacket(packet.getBytes());
                    wire.readUnsignedShort();
                    assertThat(wire.readByte()).isEqualTo((byte) 4);
                    String message = wire.readString();
                    assertThat(wire.available()).isZero();
                    return message;
                })
                .toList();
    }
}
