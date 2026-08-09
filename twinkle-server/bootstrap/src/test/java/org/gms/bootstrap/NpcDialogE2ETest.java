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
 * M3-5 验收：NPC 对话脚本（经典 v83 status 重入 + 北斗 nextlevel 函数派发）。
 *
 * <p>环境同 GamePlayE2ETest。脚本目录含两个脚本：
 * <ul>
 *   <li>{@code nps/1011000.js}：经典写法（start + action 用 status 推进）</li>
 *   <li>{@code nps/1011001.js}：nextlevel 写法（start + levelStart + levelDone 函数派发）</li>
 * </ul>
 */
class NpcDialogE2ETest {

    /** 最小地图（无怪物，专注对话）。 */
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

    /** 经典 status 重入脚本：start 发第一句，action 推进 status。 */
    private static final String CLASSIC_SCRIPT = """
            var status = 0;
            function start() {
                cm.sendNext("你好，冒险家！");
            }
            function action(mode, type, selection) {
                if (mode != 1) { cm.dispose(); return; }
                status++;
                if (status == 1) {
                    cm.sendOk("再见！");
                } else {
                    cm.dispose();
                }
            }
            """;

    /** nextlevel 函数派发脚本：start → levelYes/levelNo → levelDone。 */
    private static final String NEXTLEVEL_SCRIPT = """
            function start() {
                cm.sendYesNoLevel("No", "Yes", "开始任务吗？");
            }
            function levelYes() {
                cm.sendOkLevel("Done", "好的，任务已开始！");
            }
            function levelNo() {
                cm.dispose();
            }
            function levelDone() {
                cm.dispose();
            }
            """;

    @Test
    void npcDialogClassicAndNextLevel() throws Exception {
        // ---- 数据层：临时 SQLite + 迁移 + 账号/角色 ----
        String dbPath = Files.createTempDirectory("twinkle-npc-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("npc-e2e-" + dbPath);
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

        // ---- WZ + 脚本目录 ----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-npc");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        Path scriptDir = Files.createTempDirectory("twinkle-script-npc");
        Path npsDir = scriptDir.resolve("nps");
        Files.createDirectories(npsDir);
        Files.writeString(npsDir.resolve("1011000.js"), CLASSIC_SCRIPT);
        Files.writeString(npsDir.resolve("1011001.js"), NEXTLEVEL_SCRIPT);

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
        ScriptEngine scriptEngine = new ScriptEngine();
        ScriptManager scriptManager = new ScriptManager(scriptEngine, new ScriptRepository(scriptDir));

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
            try (Socket channel = new Socket("127.0.0.1", channelPort)) {
                channel.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(channel.getInputStream());
                OutputStream out = channel.getOutputStream();

                byte[] hello = in.readNBytes(16);
                assertThat(hello[2]).isEqualTo((byte) 83);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

                // 进图
                ByteArrayOutPacket loggedin = new ByteArrayOutPacket();
                loggedin.writeShort(RecvOpcode.PLAYER_LOGGEDIN.getValue());
                loggedin.writeInt(hero.getId().intValue());
                send(out, send, loggedin);
                // 消费 SET_FIELD（无怪物，只有 SET_FIELD）
                InPacket setField = readPacket(in, recv);
                assertThat(setField.readUnsignedShort()).isEqualTo(SendOpcode.SET_FIELD.getValue());

                ByteArrayOutPacket transfer = new ByteArrayOutPacket();
                transfer.writeShort(RecvOpcode.PLAYER_MAP_TRANSFER.getValue());
                send(out, send, transfer);

                // ---- 经典脚本：NPC 1011000 ----
                ByteArrayOutPacket npcTalk = new ByteArrayOutPacket();
                npcTalk.writeShort(RecvOpcode.NPC_TALK.getValue());
                npcTalk.writeInt(1011000);
                send(out, send, npcTalk);

                InPacket talk1 = readPacket(in, recv);
                assertThat(talk1.readUnsignedShort()).isEqualTo(SendOpcode.NPC_TALK.getValue());
                assertThat(talk1.readByte()).isEqualTo((byte) 4);      // nSpeakerTypeID = NPC
                assertThat(talk1.readInt()).isEqualTo(1011000);        // npcId
                assertThat(talk1.readByte()).isZero();                 // msgType 0
                assertThat(talk1.readByte()).isZero();                 // speaker 0
                String text1 = talk1.readString();
                assertThat(text1).contains("你好");

                // 继续：action mode=1（下一步）→ status=1 → sendOk("再见")
                ByteArrayOutPacket talkMore = new ByteArrayOutPacket();
                talkMore.writeShort(RecvOpcode.NPC_TALK_MORE.getValue());
                talkMore.writeByte(0);      // lastMsg = 0
                talkMore.writeByte(1);      // mode = 下一步
                talkMore.writeInt(0);       // selection
                send(out, send, talkMore);

                InPacket talk2 = readPacket(in, recv);
                assertThat(talk2.readUnsignedShort()).isEqualTo(SendOpcode.NPC_TALK.getValue());
                assertThat(talk2.readByte()).isEqualTo((byte) 4);
                assertThat(talk2.readInt()).isEqualTo(1011000);
                assertThat(talk2.readByte()).isZero();                 // msgType 0
                assertThat(talk2.readByte()).isZero();                 // speaker 0
                String text2 = talk2.readString();
                assertThat(text2).contains("再见");

                // 继续：mode=-1 关闭
                ByteArrayOutPacket talkClose = new ByteArrayOutPacket();
                talkClose.writeShort(RecvOpcode.NPC_TALK_MORE.getValue());
                talkClose.writeByte(0);
                talkClose.writeByte(-1);    // mode = 关闭
                talkClose.writeInt(0);
                send(out, send, talkClose);
                Thread.sleep(200);
                // 会话已关闭（无响应包，无断言可读）

                // ---- nextlevel 脚本：NPC 1011001 ----
                ByteArrayOutPacket npcTalk2 = new ByteArrayOutPacket();
                npcTalk2.writeShort(RecvOpcode.NPC_TALK.getValue());
                npcTalk2.writeInt(1011001);
                send(out, send, npcTalk2);

                InPacket nl1 = readPacket(in, recv);
                assertThat(nl1.readUnsignedShort()).isEqualTo(SendOpcode.NPC_TALK.getValue());
                assertThat(nl1.readByte()).isEqualTo((byte) 4);
                assertThat(nl1.readInt()).isEqualTo(1011001);
                assertThat(nl1.readByte()).isEqualTo((byte) 1);        // msgType 1 = 是/否
                assertThat(nl1.readByte()).isZero();                   // speaker 0
                String nlText = nl1.readString();
                assertThat(nlText).contains("开始任务");

                // 选"是"（mode=1）→ levelYes → sendOkLevel → levelDone
                ByteArrayOutPacket yes = new ByteArrayOutPacket();
                yes.writeShort(RecvOpcode.NPC_TALK_MORE.getValue());
                yes.writeByte(1);       // lastMsg = 1（是/否）
                yes.writeByte(1);       // mode = 是
                yes.writeInt(0);
                send(out, send, yes);

                InPacket nl2 = readPacket(in, recv);
                assertThat(nl2.readUnsignedShort()).isEqualTo(SendOpcode.NPC_TALK.getValue());
                assertThat(nl2.readByte()).isEqualTo((byte) 4);
                assertThat(nl2.readInt()).isEqualTo(1011001);
                assertThat(nl2.readByte()).isZero();                    // msgType 0
                assertThat(nl2.readByte()).isZero();                    // speaker 0
                String nlText2 = nl2.readString();
                assertThat(nlText2).contains("任务已开始");

                // 下一步 → levelDone → dispose
                ByteArrayOutPacket done = new ByteArrayOutPacket();
                done.writeShort(RecvOpcode.NPC_TALK_MORE.getValue());
                done.writeByte(0);
                done.writeByte(1);
                done.writeInt(0);
                send(out, send, done);
                Thread.sleep(100);
            }
        } finally {
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
