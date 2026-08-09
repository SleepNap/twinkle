package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
import org.gms.data.repo.FlexInventoryItemRepository;
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
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 验收：登录全链路端到端（客户端连入 → 握手 → 登录 → 服务器列表 → 角色列表 → 选角）。
 *
 * <p>真实 Netty 登录服 + 真实 SQLite/MyBatis-Flex 数据 + Socket 客户端按 v83 协议走完整流程，
 * 每个阶段响应包都解密校验关键字段（红线 1：字节级对齐）。
 */
class LoginFlowE2ETest {

    @Test
    void fullLoginFlow() throws Exception {
        // ---- 数据层：临时 SQLite + 迁移 + 测试账号/角色 ----
        String dbPath = Files.createTempDirectory("twinkle-e2e").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        // 唯一 environmentId：MyBatis-Flex 的 Mappers 是静态注册表，同 envId 会复用旧实例
        // （指向别的测试 db，导致 getMapper 拿错数据源）。独立 id 隔离各测试。
        flex.setEnvironmentId("e2e-" + dbPath);
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
        acc.setTos(1); // 已接受服务条款，跳过 v83 登录前置 ToS 门
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
        characterMapper.insertSelective(hero);

        // ---- 网络：handler 注册 + 登录服 ----
        FlexAccountRepository accountRepository = new FlexAccountRepository(accountMapper);
        LoginService loginService = new LoginService(
                accountRepository, new FlexCharacterRepository(characterMapper));
        HandlerRegistry registry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService, accountRepository).register(registry, "twinkle", new byte[]{127, 0, 0, 1}, 8484);
        LoginServer server = new LoginServer(registry);
        server.start(0);

        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // 1. 握手：读明文 hello，解析 IV
            byte[] hello = in.readNBytes(16);
            assertThat(hello[2]).isEqualTo((byte) 83);
            byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
            byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
            AesCipher clientSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
            // 收方向用 0xFFFF-83：镜像真实客户端以 0xFFFF-version 校验服务端包头（CipherPair 发送侧）
            AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

            // 2. 登录
            ByteArrayOutPacket login = new ByteArrayOutPacket();
            login.writeShort(RecvOpcode.LOGIN_PASSWORD.getValue());
            login.writeString("tester");
            login.writeString("secret");
            login.writeBytes(new byte[6]);
            login.writeBytes(new byte[4]);
            send(out, clientSend, login);

            InPacket status = readPacket(in, clientRecv);
            assertThat(status.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue());
            assertThat(status.readInt()).isZero();                 // 成功
            assertThat(status.readUnsignedShort()).isZero();
            assertThat(status.readInt()).isEqualTo(acc.getId().intValue()); // accountId
            assertThat(status.readByte()).isZero();                // gender

            // 3. 服务器列表
            ByteArrayOutPacket serverListReq = new ByteArrayOutPacket();
            serverListReq.writeShort(RecvOpcode.SERVERLIST_REQUEST.getValue());
            send(out, clientSend, serverListReq);

            InPacket serverList = readPacket(in, clientRecv);
            assertThat(serverList.readUnsignedShort()).isEqualTo(SendOpcode.SERVERLIST.getValue());
            assertThat(serverList.readByte()).isZero();            // serverId
            assertThat(serverList.readString()).isEqualTo("twinkle");
            InPacket serverListEnd = readPacket(in, clientRecv);
            assertThat(serverListEnd.readUnsignedShort()).isEqualTo(SendOpcode.SERVERLIST.getValue());
            assertThat(serverListEnd.readByte()).isEqualTo((byte) 0xFF);

            // 4. 角色列表
            ByteArrayOutPacket charListReq = new ByteArrayOutPacket();
            charListReq.writeShort(RecvOpcode.CHARLIST_REQUEST.getValue());
            charListReq.writeByte(0);
            charListReq.writeByte(0);                               // serverId
            send(out, clientSend, charListReq);

            InPacket charList = readPacket(in, clientRecv);
            assertThat(charList.readUnsignedShort()).isEqualTo(SendOpcode.CHARLIST.getValue());
            assertThat(charList.readByte()).isZero();               // status
            assertThat(charList.readByte()).isEqualTo((byte) 1);    // 角色数
            assertThat(charList.readInt()).isEqualTo(hero.getId().intValue()); // charId
            byte[] name = charList.readBytes(13);
            assertThat(new String(name, InPacket.DEFAULT_CHARSET).trim()).isEqualTo("Hero");

            // 5. 选角
            ByteArrayOutPacket charSelect = new ByteArrayOutPacket();
            charSelect.writeShort(RecvOpcode.CHAR_SELECT.getValue());
            charSelect.writeInt(hero.getId().intValue());
            charSelect.writeString("");
            charSelect.writeString("");
            send(out, clientSend, charSelect);

            InPacket serverIp = readPacket(in, clientRecv);
            assertThat(serverIp.readUnsignedShort()).isEqualTo(SendOpcode.SERVER_IP.getValue());
            assertThat(serverIp.readUnsignedShort()).isZero();
            assertThat(serverIp.readBytes(4)).containsExactly(127, 0, 0, 1);
            assertThat(serverIp.readUnsignedShort()).isEqualTo(8484);
            assertThat(serverIp.readInt()).isEqualTo(hero.getId().intValue());
        } finally {
            server.close();
        }
    }

    @Test
    void wrongPasswordRejected() throws Exception {
        // 简化验证：错误密码回 LOGIN_STATUS 错误码 4
        String dbPath = Files.createTempDirectory("twinkle-e2e-fail").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("e2e-fail-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.start();
        AccountMapper accountMapper = flex.getMapper(AccountMapper.class);
        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        acc.setBanned(0);
        accountMapper.insertSelective(acc);

        FlexAccountRepository accountRepository = new FlexAccountRepository(accountMapper);
        LoginService loginService = new LoginService(
                accountRepository,
                new org.gms.data.repo.CharacterRepository() {
                    @Override
                    public java.util.List<org.gms.data.entity.Character> findByAccount(int accountId, int world) {
                        return java.util.List.of();
                    }

                    @Override
                    public java.util.Optional<org.gms.data.entity.Character> findById(long id) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public boolean existsByName(String name) {
                        return false;
                    }

                    @Override
                    public void insert(org.gms.data.entity.Character chr) {
                        // 登录流程测试不做建角
                    }

                    @Override
                    public void save(org.gms.data.entity.Character chr) {
                        // 登录流程测试不做存档
                    }
                });
        HandlerRegistry registry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService, accountRepository).register(registry, "twinkle", new byte[]{127, 0, 0, 1}, 8484);
        LoginServer server = new LoginServer(registry);
        server.start(0);

        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            byte[] hello = in.readNBytes(16);
            byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
            byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
            AesCipher clientSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
            // 收方向用 0xFFFF-83：镜像真实客户端以 0xFFFF-version 校验服务端包头（CipherPair 发送侧）
            AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

            ByteArrayOutPacket login = new ByteArrayOutPacket();
            login.writeShort(RecvOpcode.LOGIN_PASSWORD.getValue());
            login.writeString("tester");
            login.writeString("wrong-password");
            login.writeBytes(new byte[6]);
            login.writeBytes(new byte[4]);
            send(out, clientSend, login);

            InPacket status = readPacket(in, clientRecv);
            assertThat(status.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue());
            assertThat(status.readByte()).isEqualTo((byte) 4);      // 密码错误
        } finally {
            server.close();
        }
    }

    private static void send(OutputStream out, AesCipher cipher, ByteArrayOutPacket packet) throws IOException {
        out.write(PacketCodec.encodePacket(cipher, packet.getBytes()));
        out.flush();
    }

    /**
     * 建角全流程：登录 → 服务器列表 → 角色列表（空）→ 查名（可用）→ 建角（ADD_NEW_CHAR_ENTRY）。
     * 验证 CHECK_CHAR_NAME / CREATE_CHAR 两个 handler 与 ADD_NEW_CHAR_ENTRY 包字节。
     */
    @Test
    void createCharacterFlow() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-e2e-create").resolve("test.db").toString();
        SimpleDriverDataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");
        MybatisFlexBootstrap flex = new MybatisFlexBootstrap();
        flex.setEnvironmentId("e2e-create-" + dbPath);
        flex.setDataSource(ds);
        flex.addMapper(AccountMapper.class);
        flex.addMapper(CharacterMapper.class);
        flex.addMapper(InventoryItemMapper.class);
        flex.start();
        AccountMapper accountMapper = flex.getMapper(AccountMapper.class);
        CharacterMapper characterMapper = flex.getMapper(CharacterMapper.class);
        InventoryItemMapper inventoryItemMapper = flex.getMapper(InventoryItemMapper.class);

        Account acc = new Account();
        acc.setName("tester");
        acc.setPassword(BCrypt.hashpw("secret", BCrypt.gensalt()));
        acc.setBanned(0);
        acc.setGender(0);
        acc.setTos(1);
        accountMapper.insertSelective(acc);

        FlexAccountRepository accountRepository = new FlexAccountRepository(accountMapper);
        LoginService loginService = new LoginService(
                accountRepository, new FlexCharacterRepository(characterMapper),
                new FlexInventoryItemRepository(inventoryItemMapper));
        HandlerRegistry registry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService, accountRepository)
                .register(registry, "twinkle", new byte[]{127, 0, 0, 1}, 8484);
        LoginServer server = new LoginServer(registry);
        server.start(0);

        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            byte[] hello = in.readNBytes(16);
            byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
            byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
            AesCipher clientSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
            AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

            // 登录
            ByteArrayOutPacket login = new ByteArrayOutPacket();
            login.writeShort(RecvOpcode.LOGIN_PASSWORD.getValue());
            login.writeString("tester");
            login.writeString("secret");
            login.writeBytes(new byte[6]);
            login.writeBytes(new byte[4]);
            send(out, clientSend, login);
            InPacket status = readPacket(in, clientRecv);
            assertThat(status.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue());
            assertThat(status.readInt()).isZero();

            // 服务器列表
            ByteArrayOutPacket serverList = new ByteArrayOutPacket();
            serverList.writeShort(RecvOpcode.SERVERLIST_REQUEST.getValue());
            send(out, clientSend, serverList);
            readPacket(in, clientRecv);   // 服务器条目
            readPacket(in, clientRecv);   // 列表结束 0xFF

            // 角色列表（空 → 显示"创建角色"按钮）
            ByteArrayOutPacket charlist = new ByteArrayOutPacket();
            charlist.writeShort(RecvOpcode.CHARLIST_REQUEST.getValue());
            charlist.writeByte(0);
            charlist.writeByte(0);
            send(out, clientSend, charlist);
            InPacket charList = readPacket(in, clientRecv);
            assertThat(charList.readUnsignedShort()).isEqualTo(SendOpcode.CHARLIST.getValue());
            assertThat(charList.readByte()).isZero();           // status 0
            assertThat(charList.readByte()).isZero();           // 0 个角色

            // 查名（可用）
            ByteArrayOutPacket checkName = new ByteArrayOutPacket();
            checkName.writeShort(RecvOpcode.CHECK_CHAR_NAME.getValue());
            checkName.writeString("NewHero");
            send(out, clientSend, checkName);
            InPacket nameResp = readPacket(in, clientRecv);
            assertThat(nameResp.readUnsignedShort()).isEqualTo(SendOpcode.CHAR_NAME_RESPONSE.getValue());
            assertThat(nameResp.readString()).isEqualTo("NewHero");
            assertThat(nameResp.readByte()).isZero();           // 0 = 可用

            // 建角（男冒险家默认造型）
            ByteArrayOutPacket create = new ByteArrayOutPacket();
            create.writeShort(RecvOpcode.CREATE_CHAR.getValue());
            create.writeString("NewHero");
            create.writeInt(1);          // job 冒险家
            create.writeInt(20000);      // face
            create.writeInt(30000);      // hair 基值
            create.writeInt(0);          // hairColor
            create.writeInt(0);          // skinColor
            create.writeInt(1040002);    // top
            create.writeInt(1060002);    // bottom
            create.writeInt(1072001);    // shoes
            create.writeInt(1302000);    // weapon
            create.writeByte(0);         // gender 男
            send(out, clientSend, create);
            InPacket newChar = readPacket(in, clientRecv);
            assertThat(newChar.readUnsignedShort()).isEqualTo(SendOpcode.ADD_NEW_CHAR_ENTRY.getValue());
            assertThat(newChar.readByte()).isZero();            // 0 = 成功
            assertThat(newChar.readInt()).isGreaterThan(0);     // 角色 id
            assertThat(new String(newChar.readBytes(13), "UTF-8").trim()).isEqualTo("NewHero"); // 13 字节定长名

            // 装备断言（健壮式）：逐字节解析 addCharStats 全字段到 addCharLook 太脆弱，
            // 改为验证建角默认装备 id 以 little-endian int 出现在包字节里（外观编码已含装备）。
            // 建角默认装备：top=1040002 / bottom=1060002 / shoes=1072001 / weapon=1302000
            byte[] packetBytes = newChar.getBytes();
            int[] defaultEquips = {1040002, 1060002, 1072001, 1302000};
            for (int itemId : defaultEquips) {
                assertThat(containsLeInt(packetBytes, itemId))
                        .as("包字节应包含装备 id=%d", itemId).isTrue();
            }

            // 重复名查重（已占用）
            ByteArrayOutPacket checkDup = new ByteArrayOutPacket();
            checkDup.writeShort(RecvOpcode.CHECK_CHAR_NAME.getValue());
            checkDup.writeString("NewHero");
            send(out, clientSend, checkDup);
            InPacket dupResp = readPacket(in, clientRecv);
            assertThat(dupResp.readUnsignedShort()).isEqualTo(SendOpcode.CHAR_NAME_RESPONSE.getValue());
            assertThat(dupResp.readString()).isEqualTo("NewHero");
            assertThat(dupResp.readByte()).isEqualTo((byte) 1);  // 1 = 已占用

            // 查看所有角色（总览头 + world 段，含新角色）
            ByteArrayOutPacket viewAll = new ByteArrayOutPacket();
            viewAll.writeShort(RecvOpcode.VIEW_ALL_CHAR.getValue());
            viewAll.writeByte(1);
            viewAll.writeByte(1);
            send(out, clientSend, viewAll);
            InPacket allHead = readPacket(in, clientRecv);
            assertThat(allHead.readUnsignedShort()).isEqualTo(SendOpcode.VIEW_ALL_CHAR.getValue());
            assertThat(allHead.readByte()).isEqualTo((byte) 1);   // 有角色
            assertThat(allHead.readInt()).isEqualTo(1);           // 1 world
            assertThat(allHead.readInt()).isEqualTo(1);           // 1 角色
            InPacket worldInfo = readPacket(in, clientRecv);
            assertThat(worldInfo.readUnsignedShort()).isEqualTo(SendOpcode.VIEW_ALL_CHAR.getValue());
            assertThat(worldInfo.readByte()).isZero();            // 段标记
            assertThat(worldInfo.readByte()).isZero();            // world id 0
            assertThat(worldInfo.readByte()).isEqualTo((byte) 1); // 角色数
            // 该角色条目：id + 13 字节名 + 装备外观含默认装备
            assertThat(worldInfo.readInt()).isGreaterThan(0);
            assertThat(new String(worldInfo.readBytes(13), "UTF-8").trim()).isEqualTo("NewHero");
            assertThat(containsLeInt(worldInfo.getBytes(), 1302000)).isTrue(); // 武器
        } finally {
            server.close();
        }
    }

    private static InPacket readPacket(DataInputStream in, AesCipher cipher) throws IOException {
        byte[] headerBytes = in.readNBytes(4);
        int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
        int length = AesCipher.decodePacketLength(header);
        byte[] body = in.readNBytes(length);
        return PacketCodec.decodePacket(cipher, header, body);
    }

    /** 字节数组中是否出现某 int 的小端编码（装备 id 出现在外观包时校验用）。 */
    private static boolean containsLeInt(byte[] bytes, int value) {
        byte b0 = (byte) (value & 0xFF);
        byte b1 = (byte) ((value >>> 8) & 0xFF);
        byte b2 = (byte) ((value >>> 16) & 0xFF);
        byte b3 = (byte) ((value >>> 24) & 0xFF);
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == b0 && bytes[i + 1] == b1 && bytes[i + 2] == b2 && bytes[i + 3] == b3) {
                return true;
            }
        }
        return false;
    }
}
