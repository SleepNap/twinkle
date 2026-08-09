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
import org.gms.channel.ChannelPacketFactory;
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
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
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
 * 怪物控制租约 E2E（事故报告 §七 完成标准 1/2：幽灵控制者租约过期 → 健康玩家接管，
 * 地图在配置上限内恢复；无受控怪玩家不受影响）。
 *
 * <p>短 TTL 驱动真实时序：A 控制怪 → 停止 MOVE_LIFE → 租约过期（手动 sweep）→
 * B 进图接管（0xEE 转移，怪物仍在图）。
 */
class MonsterLeaseE2ETest {

    /** 带怪物刷新点 + 地面的最小地图。 */
    private static final String MAP_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <imgdir name="100000000.img">
              <imgdir name="info">
                <int name="town" value="0"/>
                <int name="returnMap" value="100000000"/>
                <int name="forcedReturn" value="999999999"/>
                <int name="fieldLimit" value="0"/>
              </imgdir>
              <imgdir name="life">
                <imgdir name="0">
                  <string name="type" value="m"/>
                  <string name="id" value="100100"/>
                  <int name="x" value="100"/>
                  <int name="y" value="100"/>
                  <int name="mobTime" value="0"/>
                </imgdir>
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
    void expiredControllerReplacedByNewPlayer() throws Exception {
        // ---- 数据层 ----
        String dbPath = Files.createTempDirectory("twinkle-lease-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("lease-e2e-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        AccountMapper accountMapper = flex.getMapper(AccountMapper.class);
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword(org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt()));
        acc.setBanned(0);
        acc.setGender(0);
        accountMapper.insertSelective(acc);

        org.gms.data.entity.Character dbA = new org.gms.data.entity.Character();
        fill(dbA, acc.getId(), "Alice", 10);
        characterMapper.insertSelective(dbA);
        org.gms.data.entity.Character dbB = new org.gms.data.entity.Character();
        fill(dbB, acc.getId(), "Bob", 10);
        characterMapper.insertSelective(dbB);
        long aCharId = dbA.getId();
        long bCharId = dbB.getId();

        // ---- WZ：临时地图（带怪物）----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-lease");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        // ---- 频道服装配（短 TTL 租约：2s 过期 + 1s 冷却）----
        DefaultControllerLeaseService lease = new DefaultControllerLeaseService(2, 1, 10_000);
        CharacterLoader characterLoader = new CharacterLoader(new DefaultVersionGate());
        ChannelMapManager mapManager = new ChannelMapManager(new MapLoader(wzRoot));
        PlayerStorage players = new PlayerStorage();
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        DefaultVersionGate versionGate = new DefaultVersionGate();
        Map<Integer, ItemData> itemData = Map.of();
        Map<Integer, MobData> mobData = new HashMap<>();
        MobData snail = new MobData(100100);
        snail.setMaxHp(30);
        snail.setMaxMp(0);
        mobData.put(100100, snail);
        ItemSystem itemSystem = new ItemSystem(versionGate, itemData);
        MonsterSpawnService spawnService = new MonsterSpawnService(mobData, sessions, lease);
        CharacterRepository characterRepo = new FlexCharacterRepository(characterMapper);

        HandlerRegistry channelRegistry = new HandlerRegistry();
        new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(characterRepo, characterLoader, mapManager, players,
                        sessions, spawnService, 1, null, lease),
                new PlayerMapTransitionHandler(),
                new MovePlayerHandler(new MovementSystem(versionGate), sessions),
                new AttackHandler(new CombatSystem(versionGate), sessions, false, false),
                new AttackHandler(new CombatSystem(versionGate), sessions, true, false),
                new AttackHandler(new CombatSystem(versionGate), sessions, false, true),
                new PlayerInteractionHandler(new TradeSystem(versionGate, itemSystem), sessions),
                new NpcTalkHandler(null, itemSystem, new QuestSystem(versionGate)),
                new NpcTalkMoreHandler(),
                new UseItemHandler(itemSystem, itemData),
                null, null, null,
                new MoveLifeHandler(lease, sessions)
        ).register(channelRegistry);
        ChannelServer channelServer = new ChannelServer(channelRegistry);
        channelServer.start(0);
        int channelPort = channelServer.boundPort();

        try {
            // ---- 玩家 A 进图：成为怪物控制者（0xEE）----
            Conn a = new Conn(channelPort, aCharId);
            a.connect();
            a.login();
            InPacket aSpawn = a.read();
            assertThat(aSpawn.readUnsignedShort()).isEqualTo(SendOpcode.SPAWN_MONSTER_CONTROL.getValue());
            int mobOid = aSpawn.readInt();
            a.consumeCharInfo();
            // 从服务端注册表取 A 的会话代际
            org.gms.domain.game.lease.LeaseOwner aOwner = ownerOf(sessions, aCharId);

            // A 停止 MOVE_LIFE → 等 TTL(2s)+余量 → 手动 sweep 释放租约
            Thread.sleep(2500);
            lease.sweep(System.nanoTime());
            assertThat(lease.controlledAliveCount(aOwner)).isZero();

            // 怪物仍在图（控制权被释放，怪没消失）
            MapleMap map = mapManager.getMap(100000000);
            MapleMonster monster = map.getMonster(mobOid);
            assertThat(monster).isNotNull();
            assertThat(monster.isAlive()).isTrue();

            // ---- 玩家 B 进图：接管无主怪（0xEE 转移到 B）----
            Conn b = new Conn(channelPort, bCharId);
            b.connect();
            b.login();
            InPacket bSpawn = b.read();
            assertThat(bSpawn.readUnsignedShort()).isEqualTo(SendOpcode.SPAWN_MONSTER_CONTROL.getValue());
            int bOid = bSpawn.readInt();
            assertThat(bOid).isEqualTo(mobOid);
            b.consumeCharInfo();

            // B 的 MOVE_LIFE 续租有效：收到 MOVE_MONSTER_RESPONSE(0xF0)
            b.sendMoveLife(mobOid);
            InPacket resp = b.read();
            assertThat(resp.readUnsignedShort()).isEqualTo(SendOpcode.MOVE_MONSTER_RESPONSE.getValue());
            assertThat(resp.readInt()).isEqualTo(mobOid);

            // 怪物仍未消失、归属 B
            assertThat(map.getMonster(mobOid)).isSameAs(monster);
            assertThat(lease.isUnowned(map.getMapId(), mobOid)).isFalse();
        } finally {
            channelServer.close();
        }
    }

    /** 从会话注册表取该角色当前 owner 身份（sessionId + generation，服务器侧真实值）。 */
    private static org.gms.domain.game.lease.LeaseOwner ownerOf(PlayerSessionRegistry sessions, long charId) {
        return new org.gms.domain.game.lease.LeaseOwner(charId,
                sessions.sessionIdOf(charId), sessions.generationOf(charId));
    }

    private static void fill(org.gms.data.entity.Character c, long accountId, String name, int level) {
        c.setAccountId(accountId);
        c.setWorld(0);
        c.setName(name);
        c.setLevel(level);
        c.setJob(0);
        c.setSkinColor(0);
        c.setGender(0);
        c.setFace(20000);
        c.setHair(30000);
        c.setStr((short) 4);
        c.setDex((short) 5);
        c.setLuk((short) 4);
        c.setIntStat((short) 4);
        c.setHp((short) 50);
        c.setMp((short) 5);
        c.setMaxHp((short) 50);
        c.setMaxMp((short) 5);
        c.setMap(100000000);
        c.setSpawnPoint(0);
        c.setBuddyCapacity(25);
        c.setEquipSlots(24);
        c.setUseSlots(24);
        c.setSetupSlots(24);
        c.setEtcSlots(24);
    }

    /** 一个 Socket 客户端连接 + v83 会话（封装握手/加密/进图）。 */
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

        /** 发送 PLAYER_LOGGEDIN 进图。 */
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

        void consumeCharInfo() throws IOException {
            InPacket p = read();
            assertThat(p.readUnsignedShort()).isEqualTo(SendOpcode.SET_FIELD.getValue());
        }

        void sendMoveLife(int oid) throws IOException {
            ByteArrayOutPacket p = new ByteArrayOutPacket();
            p.writeShort(RecvOpcode.MOVE_LIFE.getValue());
            p.writeInt(oid);
            p.writeInt(1);            // moveId
            p.writeShort(100);        // startX
            p.writeShort(100);        // startY
            p.writeByte(0);           // skill = 0 普通移动
            p.writeByte(2);           // movement: 1 个片段长度
            p.writeByte(0);           // 片段内容
            p.writeByte(0);
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
