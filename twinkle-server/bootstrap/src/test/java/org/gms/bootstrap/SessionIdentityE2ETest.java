package org.gms.bootstrap;

import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.MonsterSpawnService;
import org.gms.channel.MoveLifeHandler;
import org.gms.channel.MovePlayerHandler;
import org.gms.channel.PlayerStorage;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.NpcTalkHandler;
import org.gms.channel.NpcTalkMoreHandler;
import org.gms.channel.PlayerInteractionHandler;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.UseItemHandler;
import org.gms.channel.AttackHandler;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.FlexCharacterRepository;
import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.entity.Account;
import org.gms.domain.game.Character;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.lease.DefaultControllerLeaseService;
import org.gms.domain.game.mob.MobData;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.InitializationVector;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketCodec;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.MovementSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.wz.MapLoader;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.gms.data.SimpleDriverDataSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话代际 E2E（事故报告 §七 完成标准 3：新连接认领同角色 → 旧连接迟到关闭不能移除
 * 新会话/新 Character/新地图登记，且旧态不被存档覆盖）。
 *
 * <p>真实 Netty + 两个 Socket：A 进图（gen1）→ B 同角色进图（gen2）→ A 主动 close →
 * 服务端注册表仍持 B、在线表是新 Character、地图单对象、旧代际清理被拒计数 +1。
 */
class SessionIdentityE2ETest {

    /** 无怪最小地图。 */
    private static final String MAP_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <imgdir name="100000000.img">
              <imgdir name="info">
                <int name="town" value="1"/>
                <int name="returnMap" value="100000000"/>
                <int name="forcedReturn" value="999999999"/>
                <int name="fieldLimit" value="0"/>
              </imgdir>
              <imgdir name="portal">
                <imgdir name="0">
                  <string name="pn" value="sp"/>
                  <int name="pt" value="0"/>
                  <int name="x" value="112"/>
                  <int name="y" value="197"/>
                  <int name="tm" value="999999999"/>
                </imgdir>
              </imgdir>
              <imgdir name="foothold">
                <imgdir name="0">
                  <imgdir name="0">
                    <imgdir name="1">
                      <int name="x1" value="0"/>
                      <int name="y1" value="100"/>
                      <int name="x2" value="500"/>
                      <int name="y2" value="100"/>
                    </imgdir>
                  </imgdir>
                </imgdir>
              </imgdir>
            </imgdir>
            """;

    @Test
    void lateDisconnectOfSupersededSessionDoesNotRemoveNewOne() throws Exception {
        // ---- 数据层 ----
        String dbPath = Files.createTempDirectory("twinkle-session-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("session-e2e-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        AccountMapper accountMapper = flex.getMapper(AccountMapper.class);
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        acc.setBanned(0);
        acc.setGender(0);
        accountMapper.insertSelective(acc);

        org.gms.data.entity.Character hero = new org.gms.data.entity.Character();
        hero.setAccountId(acc.getId());
        hero.setWorld(0);
        hero.setName("Hero");
        hero.setLevel(10);
        hero.setJob(0);
        hero.setSkinColor(0);
        hero.setGender(0);
        hero.setFace(20000);
        hero.setHair(30000);
        hero.setStr((short) 4);
        hero.setDex((short) 5);
        hero.setLuk((short) 4);
        hero.setIntStat((short) 4);
        hero.setHp((short) 50);
        hero.setMp((short) 5);
        hero.setMaxHp((short) 50);
        hero.setMaxMp((short) 5);
        hero.setMap(100000000);
        hero.setSpawnPoint(0);
        hero.setBuddyCapacity(25);
        hero.setEquipSlots(24);
        hero.setUseSlots(24);
        hero.setSetupSlots(24);
        hero.setEtcSlots(24);
        characterMapper.insertSelective(hero);
        long heroId = hero.getId();

        // ---- WZ ----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-session");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        // ---- 频道服装配（接断链回调：compare-and-remove + 存档）----
        DefaultControllerLeaseService lease = new DefaultControllerLeaseService(50, 15, 10_000);
        CharacterLoader characterLoader = new CharacterLoader(new DefaultVersionGate());
        ChannelMapManager mapManager = new ChannelMapManager(new MapLoader(wzRoot));
        PlayerStorage players = new PlayerStorage();
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        DefaultVersionGate versionGate = new DefaultVersionGate();
        Map<Integer, ItemData> itemData = Map.of();
        Map<Integer, MobData> mobData = new HashMap<>();
        ItemSystem itemSystem = new ItemSystem(versionGate, itemData);
        MonsterSpawnService spawnService = new MonsterSpawnService(mobData, sessions, lease);
        CharacterRepository characterRepo = new FlexCharacterRepository(characterMapper);

        ChannelServer channelServer = new ChannelServer(channelRegistry(characterRepo, characterLoader, mapManager,
                players, sessions, spawnService, itemSystem, lease), session -> {
            org.gms.domain.game.Character chr = session.getAttr("character");
            if (chr != null) {
                sessions.unregister(chr.getId(), session);
                players.remove(chr);
                if (chr.getMapObject() != null) {
                    chr.getMapObject().removeCharacter(chr);
                }
                Long gen = session.getAttr("sessionGeneration");
                if (gen != null) {
                    lease.onDisconnect(chr.getId(), session.sessionId(), gen);
                }
            }
        });
        channelServer.start(0);
        int channelPort = channelServer.boundPort();

        try {
            // ---- 会话 A 进图（gen1）----
            Conn a = new Conn(channelPort, heroId);
            a.connect();
            a.login();
            a.read();                 // SET_FIELD（无怪地图：第一个包就是 SET_FIELD）
            assertThat(sessions.generationOf(heroId)).isGreaterThan(0);
            long gen1 = sessions.generationOf(heroId);

            // ---- 会话 B 同角色进图（gen2，覆盖 A）----
            Conn b = new Conn(channelPort, heroId);
            b.connect();
            b.login();
            b.read();
            long gen2 = sessions.generationOf(heroId);
            assertThat(gen2).isGreaterThan(gen1);
            Character bChar = players.getById(heroId);
            assertThat(bChar).isNotNull();

            // ---- A 迟到关闭：compare-and-remove 拒绝，B 的会话/角色/地图登记不受影响 ----
            a.close();
            Thread.sleep(300);       // 让 Netty IO 线程处理 A 的 channelInactive
            assertThat(sessions.get(heroId)).isNotNull();       // B 的会话仍在
            assertThat(players.getById(heroId)).isSameAs(bChar);
            assertThat(sessions.supersededCleanupRejectedCount()).isGreaterThanOrEqualTo(1);
            // 地图仍只有 B 一个同 id 角色
            org.gms.domain.game.map.MapleMap map = mapManager.getMap(100000000);
            long count = map.characters().stream().filter(c -> c.getId() == heroId).count();
            assertThat(count).isEqualTo(1);

            // ---- B 仍可正常移动（新会话活着）----
            b.sendMovePlayer();
            Thread.sleep(200);
            assertThat(players.getById(heroId).getX()).isEqualTo(200);

            // ---- B 关闭：正常注销（gen2 移除，注册表清空）----
            b.close();
            Thread.sleep(300);
            assertThat(sessions.get(heroId)).isNull();
        } finally {
            channelServer.close();
        }
    }

    private static HandlerRegistry channelRegistry(CharacterRepository repo, CharacterLoader loader,
                                                   ChannelMapManager mapManager, PlayerStorage players,
                                                   PlayerSessionRegistry sessions, MonsterSpawnService spawnService,
                                                   ItemSystem itemSystem, DefaultControllerLeaseService lease) {
        DefaultVersionGate gate = new DefaultVersionGate();
        HandlerRegistry registry = new HandlerRegistry();
        new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(repo, loader, mapManager, players, sessions, spawnService, 1, null, lease),
                new PlayerMapTransitionHandler(),
                new MovePlayerHandler(new MovementSystem(gate), sessions),
                new AttackHandler(new CombatSystem(gate), sessions, false, false),
                new AttackHandler(new CombatSystem(gate), sessions, true, false),
                new AttackHandler(new CombatSystem(gate), sessions, false, true),
                new PlayerInteractionHandler(new TradeSystem(gate, itemSystem), sessions),
                new NpcTalkHandler(null, itemSystem, new QuestSystem(gate)),
                new NpcTalkMoreHandler(),
                new UseItemHandler(itemSystem, Map.of()),
                null, null, null,
                new MoveLifeHandler(lease, sessions)
        ).register(registry);
        return registry;
    }

    /** Socket 客户端连接封装。 */
    static final class Conn implements AutoCloseable {
        private final int port;
        private final long charId;
        private Socket socket;
        private DataInputStream in;
        private OutputStream out;
        private AesCipher send;
        private AesCipher recv;

        Conn(int port, long charId) {
            this.port = port;
            this.charId = charId;
        }

        void connect() throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            byte[] hello = in.readNBytes(16);
            byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
            byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
            send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
            recv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));
        }

        void login() throws IOException {
            ByteArrayOutPacket p = new ByteArrayOutPacket();
            p.writeShort(RecvOpcode.PLAYER_LOGGEDIN.getValue());
            p.writeInt((int) charId);
            write(p);
        }

        InPacket read() throws IOException {
            byte[] headerBytes = in.readNBytes(4);
            int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                    | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
            int length = AesCipher.decodePacketLength(header);
            byte[] body = in.readNBytes(length);
            return PacketCodec.decodePacket(recv, header, body);
        }

        void sendMovePlayer() throws IOException {
            ByteArrayOutPacket p = new ByteArrayOutPacket();
            p.writeShort(RecvOpcode.MOVE_PLAYER.getValue());
            p.skip(9);
            p.writeByte(1);
            p.writeByte(0);
            p.writeShort(200);
            p.writeShort(100);
            p.writeShort(0);
            p.writeShort(0);
            p.writeShort(0);
            p.writeByte(0);
            p.writeShort(0);
            write(p);
        }

        private void write(ByteArrayOutPacket p) throws IOException {
            out.write(PacketCodec.encodePacket(send, p.getBytes()));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
