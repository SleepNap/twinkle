package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.channel.AttackHandler;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.MonsterSpawnService;
import org.gms.channel.MovePlayerHandler;
import org.gms.channel.NpcTalkHandler;
import org.gms.channel.NpcTalkMoreHandler;
import org.gms.channel.PlayerInteractionHandler;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.UseItemHandler;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.script.ScriptEngine;
import org.gms.domain.script.ScriptManager;
import org.gms.domain.script.ScriptRepository;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.login.LoginService;
import org.gms.login.handler.LoginHandlerRegistrar;
import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.InitializationVector;
import org.gms.net.netty.LoginServer;
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
 * M3-5 验收：交易（双客户端）。
 *
 * <p>环境同 GamePlayE2ETest。双 Socket 客户端进图后，A 邀请 B 交易：
 * INVITE → VISIT（接受）→ SET_MESO/物品 → 双方 CONFIRM → complete 结算。
 * 断言：结算后 meso/物品正确转移。
 */
class TradeE2ETest {

    private static final String MAP_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <imgdir name="100000000.img">
              <imgdir name="info">
                <int name="town" value="1"/>
                <int name="returnMap" value="100000000"/>
                <int name="forcedReturn" value="999999999"/>
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
    void tradeBetweenTwoClients() throws Exception {
        // ---- 数据层 ----
        String dbPath = Files.createTempDirectory("twinkle-trade-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("trade-e2e-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.addMapper(CharacterMapper.class);
        flex.start();
        AccountMapper accountMapper = flex.getMapper(AccountMapper.class);
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);

        Account accA = new Account();
        accA.setName("alice");
        accA.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        accA.setBanned(0);
        accA.setGender(0);
        accountMapper.insertSelective(accA);

        Account accB = new Account();
        accB.setName("bob");
        accB.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        accB.setBanned(0);
        accB.setGender(0);
        accountMapper.insertSelective(accB);

        Character heroA = makeChar(accA, "Alice");
        Character heroB = makeChar(accB, "Bob");
        characterMapper.insertSelective(heroA);
        characterMapper.insertSelective(heroB);

        // ---- WZ + 脚本 ----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-trade");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        Path scriptDir = Files.createTempDirectory("twinkle-script-trade");
        ScriptEngine scriptEngine = new ScriptEngine();
        ScriptManager scriptManager = new ScriptManager(scriptEngine, new ScriptRepository(scriptDir));

        // ---- 频道服装配 ----
        CharacterLoader characterLoader = new CharacterLoader(new DefaultVersionGate());
        ChannelMapManager mapManager = new ChannelMapManager(new MapLoader(wzRoot));
        PlayerStorage players = new PlayerStorage();
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        DefaultVersionGate versionGate = new DefaultVersionGate();
        Map<Integer, ItemData> itemData = new HashMap<>();
        Map<Integer, MobData> mobData = new HashMap<>();
        ItemSystem itemSystem = new ItemSystem(versionGate, itemData);
        MonsterSpawnService spawnService = new MonsterSpawnService(mobData, sessions,
                new org.gms.domain.game.lease.DefaultControllerLeaseService(50, 15, 10_000));

        HandlerRegistry channelRegistry = new HandlerRegistry();
        new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(
                        new FlexCharacterRepository(characterMapper), characterLoader, mapManager, players,
                        sessions, spawnService, 1),
                new PlayerMapTransitionHandler(),
                new MovePlayerHandler(new MovementSystem(versionGate), sessions),
                new AttackHandler(new CombatSystem(versionGate), sessions, false, false),
                new AttackHandler(new CombatSystem(versionGate), sessions, true, false),
                new AttackHandler(new CombatSystem(versionGate), sessions, false, true),
                new PlayerInteractionHandler(new TradeSystem(versionGate, itemSystem), sessions),
                new NpcTalkHandler(scriptManager, itemSystem, new QuestSystem(versionGate)),
                new NpcTalkMoreHandler(),
                new UseItemHandler(itemSystem, itemData)
        ).register(channelRegistry);
        ChannelServer channelServer = new ChannelServer(channelRegistry);
        channelServer.start(0);
        int channelPort = channelServer.boundPort();

        try {
            // ---- 两个客户端进图 ----
            try (Socket sockA = new Socket("127.0.0.1", channelPort);
                 Socket sockB = new Socket("127.0.0.1", channelPort)) {
                sockA.setSoTimeout(5000);
                sockB.setSoTimeout(5000);

                Client a = Client.login(sockA, heroA.getId());
                Client b = Client.login(sockB, heroB.getId());

                // A 邀请 B（目标角色 id = heroB）
                ByteArrayOutPacket invite = new ByteArrayOutPacket();
                invite.writeShort(RecvOpcode.PLAYER_INTERACTION.getValue());
                invite.writeByte(0x02);         // INVITE
                invite.writeInt(heroB.getId().intValue());
                a.send(invite);

                // B 收到邀请包（PLAYER_INTERACTION 0x13A, INVITE + 3 + 名字 + 4B）
                InPacket bInvite = b.readPacket();
                assertThat(bInvite.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(bInvite.readByte()).isEqualTo((byte) 0x02);
                assertThat(bInvite.readByte()).isEqualTo((byte) 3);
                String bName = bInvite.readString();
                assertThat(bName).isEqualTo("Alice");

                // B 接受（VISIT）
                ByteArrayOutPacket visit = new ByteArrayOutPacket();
                visit.writeShort(RecvOpcode.PLAYER_INTERACTION.getValue());
                visit.writeByte(0x04);          // VISIT
                b.send(visit);

                // 双方收到进窗口包：A 收 partnerAdd(VISIT) + room(ROOM)，B 收 room(ROOM)
                InPacket aPartner = a.readPacket();
                assertThat(aPartner.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(aPartner.readByte()).isEqualTo((byte) 0x04);  // VISIT
                InPacket aRoom = a.readPacket();
                assertThat(aRoom.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(aRoom.readByte()).isEqualTo((byte) 0x05);     // ROOM
                InPacket bRoom = b.readPacket();
                assertThat(bRoom.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(bRoom.readByte()).isEqualTo((byte) 0x05);     // ROOM

                // B 发 SET_MESO 1000
                ByteArrayOutPacket meso = new ByteArrayOutPacket();
                meso.writeShort(RecvOpcode.PLAYER_INTERACTION.getValue());
                meso.writeByte(0x10);           // SET_MESO
                meso.writeInt(1000);
                b.send(meso);
                Thread.sleep(100);
                // 双方 meso 广播（SET_MESO + number + int；number 是 B 的槽位 1，双方都看到 1）
                InPacket aMeso = a.readPacket();
                assertThat(aMeso.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(aMeso.readByte()).isEqualTo((byte) 0x10);
                assertThat(aMeso.readByte()).isEqualTo((byte) 1);       // B 是槽位 1
                assertThat(aMeso.readInt()).isEqualTo(1000);
                InPacket bMeso = b.readPacket();
                assertThat(bMeso.readUnsignedShort()).isEqualTo(SendOpcode.PLAYER_INTERACTION.getValue());
                assertThat(bMeso.readByte()).isEqualTo((byte) 0x10);
                assertThat(bMeso.readByte()).isEqualTo((byte) 1);

                // 双方 CONFIRM
                ByteArrayOutPacket confirmA = new ByteArrayOutPacket();
                confirmA.writeShort(RecvOpcode.PLAYER_INTERACTION.getValue());
                confirmA.writeByte(0x11);       // CONFIRM
                a.send(confirmA);
                ByteArrayOutPacket confirmB = new ByteArrayOutPacket();
                confirmB.writeShort(RecvOpcode.PLAYER_INTERACTION.getValue());
                confirmB.writeByte(0x11);
                b.send(confirmB);
                Thread.sleep(100);

                // 结算后：双方初始 5000，B 出 1000 → A=6000，B=4000
                org.gms.domain.game.Character chrA = players.getById(heroA.getId());
                org.gms.domain.game.Character chrB = players.getById(heroB.getId());
                assertThat(chrA.getMeso()).isEqualTo(6000);
                assertThat(chrB.getMeso()).isEqualTo(4000);
            }
        } finally {
            channelServer.close();
        }
    }

    private static Character makeChar(Account acc, String name) {
        Character c = new Character();
        c.setAccountid(acc.getId());
        c.setWorld(0);
        c.setName(name);
        c.setLevel(10);
        c.setJob(0);
        c.setSkincolor(0);
        c.setGender(0);
        c.setFace(20000);
        c.setHair(30000);
        c.setStr((short) 4);
        c.setDex((short) 5);
        c.setLuk((short) 4);
        c.setIntStat((short) 4);
        c.setHp((short) 50);
        c.setMp((short) 5);
        c.setMaxhp((short) 50);
        c.setMaxmp((short) 5);
        c.setMap(100000000);
        c.setSpawnpoint(0);
        c.setBuddyCapacity(25);
        c.setEquipslots(24);
        c.setUseslots(24);
        c.setSetupslots(24);
        c.setEtcslots(24);
        c.setMeso(5000);        // 初始金币（交易 SET_MESO 校验持有量）
        return c;
    }

    /** 客户端 helper（握手 + 进图 + 加密收发）。 */
    private static final class Client implements AutoCloseable {
        final DataInputStream in;
        final OutputStream out;
        final AesCipher send;
        final AesCipher recv;

        Client(DataInputStream in, OutputStream out, AesCipher send, AesCipher recv) {
            this.in = in;
            this.out = out;
            this.send = send;
            this.recv = recv;
        }

        static Client login(Socket socket, long charId) throws IOException {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            byte[] hello = in.readNBytes(16);
            byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
            byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
            AesCipher send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
            AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) 83);
            Client c = new Client(in, out, send, recv);
            ByteArrayOutPacket loggedin = new ByteArrayOutPacket();
            loggedin.writeShort(RecvOpcode.PLAYER_LOGGEDIN.getValue());
            loggedin.writeInt((int) charId);
            c.send(loggedin);
            c.readPacket();     // SET_FIELD
            return c;
        }

        void send(ByteArrayOutPacket packet) throws IOException {
            out.write(PacketCodec.encodePacket(send, packet.getBytes()));
            out.flush();
        }

        InPacket readPacket() throws IOException {
            byte[] headerBytes = in.readNBytes(4);
            int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                    | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
            int length = AesCipher.decodePacketLength(header);
            byte[] body = in.readNBytes(length);
            return PacketCodec.decodePacket(recv, header, body);
        }

        @Override
        public void close() throws IOException {
            in.close();
            out.close();
        }
    }
}
