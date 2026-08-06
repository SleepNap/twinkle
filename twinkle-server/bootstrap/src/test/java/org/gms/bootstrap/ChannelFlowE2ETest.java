package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelServer;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerLoggedinHandler;
import org.gms.channel.PlayerMapTransitionHandler;
import org.gms.channel.PlayerStorage;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.domain.game.map.MapleMap;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 验收：进图全链路端到端（登录 → 选角 → 连频道服 → PLAYER_LOGGEDIN → SET_FIELD → 地图转移完成）。
 *
 * <p>真实 Netty 登录服 + 频道服 + 真实 SQLite/MyBatis-Flex 数据 + 临时 WZ 地图数据，
 * Socket 客户端按 v83 协议走完整流程，SET_FIELD 包逐字段字节级校验（红线 1）。
 */
class ChannelFlowE2ETest {

    /** 最小地图 XML（info/portal/foothold），E2E 自包含不依赖外部 WZ。 */
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
    void fullLoginToMapFlow() throws Exception {
        // ---- 数据层：临时 SQLite + 迁移 + 账号/角色 ----
        String dbPath = Files.createTempDirectory("twinkle-channel-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("channel-e2e-" + dbPath);
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

        Character hero = new Character();
        hero.setAccountid(acc.getId());
        hero.setWorld(0);
        hero.setName("Hero");
        hero.setLevel(10);
        hero.setJob(0);
        hero.setSkincolor(0);
        hero.setGender(0);
        hero.setFace(20000);
        hero.setHair(30000);
        hero.setStr((short) 4);
        hero.setDex((short) 5);
        hero.setLuk((short) 4);
        hero.setIntStat((short) 4);
        hero.setHp((short) 50);
        hero.setMp((short) 5);
        hero.setMaxhp((short) 50);
        hero.setMaxmp((short) 5);
        hero.setMap(100000000);
        hero.setSpawnpoint(0);
        hero.setBuddyCapacity(25);      // v83 好友列表默认容量（insertSelective 会插 0 覆盖 DB DEFAULT，须显式设）
        hero.setEquipslots(24);         // 背包槽位上限（同上，v83 默认 24）
        hero.setUseslots(24);
        hero.setSetupslots(24);
        hero.setEtcslots(24);
        characterMapper.insertSelective(hero);

        // ---- WZ：临时目录写最小地图 ----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-e2e");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        // ---- 频道服（先启动拿端口）----
        CharacterLoader characterLoader = new CharacterLoader(new DefaultVersionGate());
        ChannelMapManager mapManager = new ChannelMapManager(new MapLoader(wzRoot));
        PlayerStorage players = new PlayerStorage();
        HandlerRegistry channelRegistry = new HandlerRegistry();
        new ChannelHandlerRegistrar(
                new PlayerLoggedinHandler(
                        new FlexCharacterRepository(characterMapper), characterLoader, mapManager, players, 1),
                new PlayerMapTransitionHandler()
        ).register(channelRegistry);
        ChannelServer channelServer = new ChannelServer(channelRegistry);
        channelServer.start(0);
        int channelPort = channelServer.boundPort();

        // ---- 登录服（频道地址指向频道服端口）----
        LoginService loginService = new LoginService(
                new FlexAccountRepository(accountMapper), new FlexCharacterRepository(characterMapper));
        HandlerRegistry loginRegistry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService)
                .register(loginRegistry, "twinkle", new byte[]{127, 0, 0, 1}, channelPort);
        LoginServer loginServer = new LoginServer(loginRegistry);
        loginServer.start(0);

        try {
            // ---- 登录链路（Socket 模拟客户端：握手 → 登录 → 列表 → 选角）----
            try (Socket login = new Socket("127.0.0.1", loginServer.boundPort())) {
                login.setSoTimeout(5000);
                DataInputStream loginIn = new DataInputStream(login.getInputStream());
                OutputStream loginOut = login.getOutputStream();

                byte[] hello = loginIn.readNBytes(16);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher loginSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher loginRecv = new AesCipher(InitializationVector.of(sendIv), (short) 83);

                ByteArrayOutPacket loginPkt = new ByteArrayOutPacket();
                loginPkt.writeShort(RecvOpcode.LOGIN_PASSWORD.getValue());
                loginPkt.writeString("tester");
                loginPkt.writeString("secret");
                loginPkt.writeBytes(new byte[6]);
                loginPkt.writeBytes(new byte[4]);
                send(loginOut, loginSend, loginPkt);
                InPacket status = readPacket(loginIn, loginRecv);
                assertThat(status.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue());
                assertThat(status.readInt()).isZero();

                ByteArrayOutPacket serverListReq = new ByteArrayOutPacket();
                serverListReq.writeShort(RecvOpcode.SERVERLIST_REQUEST.getValue());
                send(loginOut, loginSend, serverListReq);
                readPacket(loginIn, loginRecv);   // SERVERLIST
                readPacket(loginIn, loginRecv);   // SERVERLIST 结束

                ByteArrayOutPacket charListReq = new ByteArrayOutPacket();
                charListReq.writeShort(RecvOpcode.CHARLIST_REQUEST.getValue());
                charListReq.writeByte(0);
                charListReq.writeByte(0);
                send(loginOut, loginSend, charListReq);
                readPacket(loginIn, loginRecv);   // CHARLIST

                ByteArrayOutPacket charSelect = new ByteArrayOutPacket();
                charSelect.writeShort(RecvOpcode.CHAR_SELECT.getValue());
                charSelect.writeInt(hero.getId().intValue());
                charSelect.writeString("");
                charSelect.writeString("");
                send(loginOut, loginSend, charSelect);

                InPacket serverIp = readPacket(loginIn, loginRecv);
                assertThat(serverIp.readUnsignedShort()).isEqualTo(SendOpcode.SERVER_IP.getValue());
                assertThat(serverIp.readUnsignedShort()).isZero();
                assertThat(serverIp.readBytes(4)).containsExactly(127, 0, 0, 1);
                assertThat(serverIp.readUnsignedShort()).isEqualTo(channelPort);
                assertThat(serverIp.readInt()).isEqualTo(hero.getId().intValue());
            } // 登录连接关闭，客户端重连频道服

            // ---- 频道链路：握手 → PLAYER_LOGGEDIN → SET_FIELD → PLAYER_MAP_TRANSFER ----
            try (Socket channel = new Socket("127.0.0.1", channelPort)) {
                channel.setSoTimeout(5000);
                DataInputStream chIn = new DataInputStream(channel.getInputStream());
                OutputStream chOut = channel.getOutputStream();

                byte[] hello = chIn.readNBytes(16);
                assertThat(hello[2]).isEqualTo((byte) 83);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher chSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher chRecv = new AesCipher(InitializationVector.of(sendIv), (short) 83);

                // PLAYER_LOGGEDIN
                ByteArrayOutPacket loggedin = new ByteArrayOutPacket();
                loggedin.writeShort(RecvOpcode.PLAYER_LOGGEDIN.getValue());
                loggedin.writeInt(hero.getId().intValue());
                send(chOut, chSend, loggedin);

                // SET_FIELD：逐字段字节级校验（红线 1）
                InPacket setField = readPacket(chIn, chRecv);
                assertThat(setField.readUnsignedShort()).isEqualTo(SendOpcode.SET_FIELD.getValue());
                assertThat(setField.readInt()).isZero();            // channel-1
                assertThat(setField.readByte()).isEqualTo((byte) 1);
                assertThat(setField.readByte()).isEqualTo((byte) 1);
                assertThat(setField.readShort()).isZero();
                setField.readInt();
                setField.readInt();
                setField.readInt();                                 // 3 随机 int

                assertThat(setField.readLong()).isEqualTo(-1L);     // addCharacterInfo 头
                assertThat(setField.readByte()).isZero();
                assertThat(setField.readInt()).isEqualTo(hero.getId().intValue()); // charStats.id
                byte[] name = setField.readBytes(13);
                assertThat(new String(name, InPacket.DEFAULT_CHARSET).trim()).isEqualTo("Hero");
                assertThat(setField.readByte()).isZero();           // gender
                assertThat(setField.readByte()).isZero();           // skincolor
                assertThat(setField.readInt()).isEqualTo(20000);    // face
                assertThat(setField.readInt()).isEqualTo(30000);    // hair
                setField.readLong();                                // pet x3
                setField.readLong();
                setField.readLong();
                assertThat(setField.readByte()).isEqualTo((byte) 10); // level
                assertThat(setField.readShort()).isZero();          // job
                assertThat(setField.readShort()).isEqualTo((short) 4); // str
                assertThat(setField.readShort()).isEqualTo((short) 5); // dex
                assertThat(setField.readShort()).isEqualTo((short) 4); // int
                assertThat(setField.readShort()).isEqualTo((short) 4); // luk
                assertThat(setField.readShort()).isEqualTo((short) 50); // hp
                assertThat(setField.readShort()).isEqualTo((short) 50); // maxhp
                assertThat(setField.readShort()).isEqualTo((short) 5); // mp
                assertThat(setField.readShort()).isEqualTo((short) 5); // maxmp
                assertThat(setField.readShort()).isZero();          // ap
                assertThat(setField.readShort()).isZero();          // remainingSp
                assertThat(setField.readInt()).isZero();            // exp
                assertThat(setField.readShort()).isZero();          // fame
                assertThat(setField.readInt()).isZero();            // gachaExp
                assertThat(setField.readInt()).isEqualTo(100000000); // mapId
                assertThat(setField.readByte()).isZero();           // spawnPoint
                setField.readInt();                                 // charStats 末尾 0
                assertThat(setField.readByte()).isEqualTo((byte) 25); // buddyCapacity
                assertThat(setField.readByte()).isZero();           // linkedName
                assertThat(setField.readInt()).isZero();            // meso
                // 背包段：5 槽位 + long 时间 + 分隔符（宽度差异）
                assertThat(setField.readByte()).isEqualTo((byte) 24); // equipSlots
                assertThat(setField.readByte()).isEqualTo((byte) 24); // useSlots
                assertThat(setField.readByte()).isEqualTo((byte) 24); // setupSlots
                assertThat(setField.readByte()).isEqualTo((byte) 24); // etcSlots
                assertThat(setField.readByte()).isEqualTo((byte) 100); // cashSlots
                setField.readLong();                                // ZERO_TIME
                assertThat(setField.readShort()).isZero();          // equipped 结束
                assertThat(setField.readShort()).isZero();          // cash 结束
                assertThat(setField.readInt()).isZero();            // equip 背包结束（use 起始）
                assertThat(setField.readByte()).isZero();           // use 结束
                assertThat(setField.readByte()).isZero();           // setup 结束
                assertThat(setField.readByte()).isZero();           // etc 结束
                assertThat(setField.readByte()).isZero();           // skills start
                assertThat(setField.readShort()).isZero();          // 技能数
                assertThat(setField.readShort()).isZero();          // cooldowns
                assertThat(setField.readShort()).isZero();          // 进行中任务
                assertThat(setField.readShort()).isZero();          // 已完成任务
                assertThat(setField.readShort()).isZero();          // miniGame
                assertThat(setField.readShort()).isZero();          // crush rings
                assertThat(setField.readShort()).isZero();          // friendship rings
                for (int i = 0; i < 15; i++) {                      // trock + viptrock
                    assertThat(setField.readInt()).isZero();
                }
                assertThat(setField.readInt()).isZero();            // monsterbook cover
                assertThat(setField.readByte()).isZero();
                assertThat(setField.readShort()).isZero();          // 卡片数
                assertThat(setField.readShort()).isZero();          // newYear
                assertThat(setField.readShort()).isZero();          // areaInfo
                assertThat(setField.readShort()).isZero();          // 结尾

                // PLAYER_MAP_TRANSFER：客户端加载完成
                ByteArrayOutPacket transfer = new ByteArrayOutPacket();
                transfer.writeShort(RecvOpcode.PLAYER_MAP_TRANSFER.getValue());
                send(chOut, chSend, transfer);

                // 服务端内存态：在线表 + 地图持有角色
                assertThat(players.count()).isEqualTo(1);
                MapleMap map = mapManager.getMap(100000000);
                assertThat(map.characterCount()).isEqualTo(1);
            }
        } finally {
            loginServer.close();
            channelServer.close();
        }
    }

    private static void send(OutputStream out, AesCipher cipher, ByteArrayOutPacket packet) throws IOException {
        out.write(PacketCodec.encodePacket(cipher, packet.getBytes()));
        out.flush();
    }

    private static InPacket readPacket(DataInputStream in, AesCipher cipher) throws IOException {
        byte[] headerBytes = in.readNBytes(4);
        int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
        int length = AesCipher.decodePacketLength(header);
        byte[] body = in.readNBytes(length);
        return PacketCodec.decodePacket(cipher, header, body);
    }
}
