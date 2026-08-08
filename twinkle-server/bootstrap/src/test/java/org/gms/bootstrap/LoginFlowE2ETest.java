package org.gms.bootstrap;

import com.mybatisflex.core.MybatisFlexBootstrap;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.mapper.AccountMapper;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.migrate.MigrationRunner;
import org.gms.data.repo.FlexAccountRepository;
import org.gms.data.repo.FlexCharacterRepository;
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
        characterMapper.insertSelective(hero);

        // ---- 网络：handler 注册 + 登录服 ----
        LoginService loginService = new LoginService(
                new FlexAccountRepository(accountMapper), new FlexCharacterRepository(characterMapper));
        HandlerRegistry registry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService).register(registry, "twinkle", new byte[]{127, 0, 0, 1}, 8484);
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
            AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) 83);

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

        LoginService loginService = new LoginService(
                new FlexAccountRepository(accountMapper),
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
                    public void save(org.gms.data.entity.Character chr) {
                        // 登录流程测试不做存档
                    }
                });
        HandlerRegistry registry = new HandlerRegistry();
        new LoginHandlerRegistrar(loginService).register(registry, "twinkle", new byte[]{127, 0, 0, 1}, 8484);
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
            AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) 83);

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

    private static InPacket readPacket(DataInputStream in, AesCipher cipher) throws IOException {
        byte[] headerBytes = in.readNBytes(4);
        int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
        int length = AesCipher.decodePacketLength(header);
        byte[] body = in.readNBytes(length);
        return PacketCodec.decodePacket(cipher, header, body);
    }
}
