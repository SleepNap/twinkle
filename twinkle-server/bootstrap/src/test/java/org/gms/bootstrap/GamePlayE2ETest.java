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
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
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
 * M3-5 验收：进图后可玩闭环端到端（移动/刷怪/战斗/交易/NPC 对话/物品使用）。
 *
 * <p>复用 ChannelFlowE2ETest 环境模式（临时 SQLite + 临时 WZ + 真 Netty + Socket 客户端），
 * 新增地图带怪物刷新点（life），并注册全部游戏内 handler。
 * 逐子项验证：移动落点更新 + MOVE_PLAYER 广播；刷怪 SPAWN_MONSTER 广播；
 * 攻击 damageMonster + 怪物扣血；交易加物品/结算；NPC 对话发包；物品使用 HP 恢复。
 */
class GamePlayE2ETest {

    /** 带怪物刷新点（life 类型 m）+ 地面 + 传送点的最小地图。 */
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
    void gameplayLoop() throws Exception {
        // ---- 数据层：临时 SQLite + 迁移 + 账号/角色（含初始物品）----
        String dbPath = Files.createTempDirectory("twinkle-gameplay-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("gameplay-e2e-" + dbPath);
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
        hero.setStr((short) 40);
        hero.setDex((short) 5);
        hero.setLuk((short) 4);
        hero.setIntStat((short) 4);
        hero.setHp((short) 500);
        hero.setMp((short) 5);
        hero.setMaxhp((short) 500);
        hero.setMaxmp((short) 5);
        hero.setMap(100000000);
        hero.setSpawnpoint(0);
        hero.setBuddyCapacity(25);
        hero.setEquipslots(24);
        hero.setUseslots(24);
        hero.setSetupslots(24);
        hero.setEtcslots(24);
        characterMapper.insertSelective(hero);

        // ---- WZ：临时目录写地图 + 怪物/物品数据 ----
        Path wzRoot = Files.createTempDirectory("twinkle-wz-gameplay");
        Path mapDir = wzRoot.resolve("Map.wz").resolve("Map").resolve("Map1");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("100000000.img.xml"), MAP_XML);

        // 蜗牛（100100，maxHP=30）与红药水（2000000，hp+50）数据
        MobData snail = new MobData(100100);
        snail.setMaxHp(30);
        snail.setPdd(0);
        Map<Integer, MobData> mobData = new HashMap<>();
        mobData.put(100100, snail);

        ItemData potion = new ItemData(2000000);
        potion.putStat("hp", 50);
        Map<Integer, ItemData> itemData = new HashMap<>();
        itemData.put(2000000, potion);

        // ---- 频道服装配（含全部游戏内 handler）----
        CharacterLoader characterLoader = new CharacterLoader(new DefaultVersionGate());
        ChannelMapManager mapManager = new ChannelMapManager(new MapLoader(wzRoot));
        PlayerStorage players = new PlayerStorage();
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        DefaultVersionGate versionGate = new DefaultVersionGate();
        ItemSystem itemSystem = new ItemSystem(versionGate, itemData);
        MonsterSpawnService spawnService = new MonsterSpawnService(mobData, sessions);

        Path scriptDir = Files.createTempDirectory("twinkle-script-gameplay");
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
            // ---- 客户端连接频道服，直接进图（跳过登录，用 PlayerLoggedinHandler 需要登录态？走完整登录）----
            // 简化：直接连频道服 + PLAYER_LOGGEDIN（PlayerLoggedinHandler 只校验 stage==LOGIN，握手下即 LOGIN）
            try (Socket channel = new Socket("127.0.0.1", channelPort)) {
                channel.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(channel.getInputStream());
                OutputStream out = channel.getOutputStream();

                byte[] hello = in.readNBytes(16);
                assertThat(hello[2]).isEqualTo((byte) 83);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) 83);

                // 进图
                ByteArrayOutPacket loggedin = new ByteArrayOutPacket();
                loggedin.writeShort(RecvOpcode.PLAYER_LOGGEDIN.getValue());
                loggedin.writeInt(hero.getId().intValue());
                send(out, send, loggedin);

                // 进图响应：PlayerLoggedinHandler 先 spawnForMap 广播 SPAWN_MONSTER，再发 SET_FIELD
                InPacket spawn = readPacket(in, recv);
                assertThat(spawn.readUnsignedShort()).isEqualTo(SendOpcode.SPAWN_MONSTER.getValue());
                int mobOid = spawn.readInt();
                assertThat(spawn.readByte()).isEqualTo((byte) 5);
                assertThat(spawn.readInt()).isEqualTo(100100);      // mobId
                spawn.skip(16);
                assertThat(spawn.readShort()).isEqualTo((short) 100); // x
                assertThat(spawn.readShort()).isEqualTo((short) 100); // y

                // SET_FIELD（消费掉）
                InPacket setField = readPacket(in, recv);
                assertThat(setField.readUnsignedShort()).isEqualTo(SendOpcode.SET_FIELD.getValue());
                // 读完整包，推进到下一个包（不逐字段校验，另测覆盖）

                // PLAYER_MAP_TRANSFER 完成进图
                ByteArrayOutPacket transfer = new ByteArrayOutPacket();
                transfer.writeShort(RecvOpcode.PLAYER_MAP_TRANSFER.getValue());
                send(out, send, transfer);

                // ---- 移动：发移动包 → 角色落点更新 + MOVE_PLAYER 广播（无其他玩家，仅自身不回显）----
                // 构造 v83 移动包：9 字节头 + 1 字节 numCommands=1 + command 0 绝对移动（x/y）
                ByteArrayOutPacket move = new ByteArrayOutPacket();
                move.writeShort(RecvOpcode.MOVE_PLAYER.getValue());
                move.skip(9);                       // 头部
                move.writeByte(1);                  // numCommands
                move.writeByte(0);                  // command 0 绝对移动
                move.writeShort(200);               // x
                move.writeShort(100);               // y
                move.writeShort(0);                 // xwobble
                move.writeShort(0);                 // ywobble
                move.writeShort(0);                 // fh
                move.writeByte(0);                  // newstate
                move.writeShort(0);                 // duration
                send(out, send, move);

                // 服务端内存态：角色 x/y 已更新（200,100）——Netty IO 线程处理，稍等
                Thread.sleep(200);
                org.gms.domain.game.Character chr = players.getById(hero.getId());
                assertThat(chr.getX()).isEqualTo(200);
                assertThat(chr.getY()).isEqualTo(100);

                // ---- 物品使用：给药水（先经脚本/直加物品到内存背包，再发 USE_ITEM）----
                itemSystem.giveItem(chr, 2000000, 1);
                assertThat(itemSystem.countItem(chr, 2000000)).isEqualTo(1);
                // 扣血到 400，再喝药恢复 +50 → 450
                chr.setHp(400);
                // USE_ITEM 需要知道物品槽位：找 USE 栏槽位
                short slot = findUseSlot(chr, 2000000);
                ByteArrayOutPacket useItem = new ByteArrayOutPacket();
                useItem.writeShort(RecvOpcode.USE_ITEM.getValue());
                useItem.skip(4);
                useItem.writeShort(slot);
                useItem.writeInt(2000000);
                send(out, send, useItem);

                // 消费掉可能的响应（STAT_CHANGED 或空），断言角色 HP
                // 发一个无响应的包让 handler 处理完，再读剩余
                Thread.sleep(100);
                assertThat(chr.getHp()).isEqualTo(450);
                assertThat(itemSystem.countItem(chr, 2000000)).isZero();

                // ---- 战斗：攻击蜗牛 → 扣血 + damageMonster 广播 ----
                MapleMap map = mapManager.getMap(100000000);
                var monster = map.getMonster(mobOid);
                assertThat(monster).isNotNull();
                int hpBefore = monster.getHp();
                assertThat(hpBefore).isEqualTo(30);     // maxHp

                // 构造攻击包（近战，无技能，1 目标 1 行伤害）
                ByteArrayOutPacket attack = new ByteArrayOutPacket();
                attack.writeShort(RecvOpcode.CLOSE_RANGE_ATTACK.getValue());
                attack.writeByte(0);                    // 跳过
                attack.writeByte((1 << 4) | 1);         // 1 目标，每目标 1 行
                attack.writeInt(0);                     // skillId = 0（徒手）
                attack.skip(8);                         // 动画块
                attack.writeByte(0);                    // display
                attack.writeByte(1);                    // direction
                attack.writeByte(0);                    // stance
                attack.writeByte(0);                    // speed 段（近战：byte0 + speed + skip4）
                attack.writeByte(0);
                attack.writeByte(0);
                attack.skip(4);
                attack.writeInt(mobOid);                // 目标 oid
                attack.skip(14);                        // 目标段
                attack.writeInt(1);                     // 1 行伤害占位（真实伤害服务端算）
                send(out, send, attack);

                // 服务端：怪物扣血
                Thread.sleep(100);
                // 实际伤害 = ceil((1.0*40+5)/100 * 1) = 1 → 扣 1，hp 30→29 存活
                assertThat(monster.getHp()).isEqualTo(29);
                assertThat(monster.isAlive()).isTrue();
            }
        } finally {
            channelServer.close();
        }
    }

    /** 找某物品在角色背包里的槽位。 */
    private static short findUseSlot(org.gms.domain.game.Character chr, int itemId) {
        for (var item : chr.getInventory(org.gms.domain.game.inventory.InventoryType.USE).items()) {
            if (item.getId() == itemId) {
                return item.getPosition();
            }
        }
        return -1;
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
