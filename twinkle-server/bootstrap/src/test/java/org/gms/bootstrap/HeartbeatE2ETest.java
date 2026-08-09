package org.gms.bootstrap;

import org.gms.net.netty.HeartbeatConfig;
import org.gms.net.netty.LoginServer;
import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.InitializationVector;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketCodec;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分阶段心跳 E2E（事故报告 §七 完成标准 4 + §5.3：readerIdle → PING → PONG deadline）。
 *
 * <p>用毫秒级微小心跳配置驱动：静默连接 → 超时关闭；回 PONG → 跨多周期保持；
 * 停在 LOGIN 阶段且正常回心跳 → 长停留不断连（登录/选角/创建角色界面不被误伤）。
 */
class HeartbeatE2ETest {

    /** 毫秒级心跳：150ms 空闲探测 + 150ms 超时。 */
    private static final HeartbeatConfig FAST = new HeartbeatConfig(150, 150);

    private static final int SERVER_PORT = 0;

    @Test
    void silentConnectionClosedAfterTimeout() throws Exception {
        LoginServer server = new LoginServer(new HandlerRegistry(), FAST);
        server.start(SERVER_PORT);
        try {
            int port = server.boundPort();
            // 连接后只读 hello，此后静默（不回任何包）
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] hello = in.readNBytes(16);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));
                // 读 PING（约 150ms 后）
                InPacket ping = readPacket(in, recv);
                assertThat(ping.readUnsignedShort()).isEqualTo(0x11);   // SendOpcode.PING
                // 不回应 → 约 150ms 后服务端关闭（PONG 超时）→ 读到 EOF
                assertThat(in.read()).isEqualTo(-1);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void pongKeepsConnectionAliveAcrossProbes() throws Exception {
        LoginServer server = new LoginServer(new HandlerRegistry(), FAST);
        server.start(SERVER_PORT);
        try {
            int port = server.boundPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                byte[] hello = in.readNBytes(16);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

                // 跨越多个探测周期：收到 PING 即回空 PONG，连接应持续保持
                for (int i = 0; i < 3; i++) {
                    InPacket ping = readPacket(in, recv);
                    assertThat(ping.readUnsignedShort()).isEqualTo(0x11);
                    ByteArrayOutPacket pong = new ByteArrayOutPacket();
                    pong.writeShort(RecvOpcode.PONG.getValue());
                    send(out, send, pong);
                }
                // 连接仍 alive（close 前服务端不会先关）
                assertThat(socket.isClosed()).isFalse();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void staysInLoginStageAndKeepsConnectionWithHeartbeat() throws Exception {
        LoginServer server = new LoginServer(new HandlerRegistry(), FAST);
        server.start(SERVER_PORT);
        try {
            int port = server.boundPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                byte[] hello = in.readNBytes(16);
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                AesCipher send = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher recv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

                // 停在 LOGIN 阶段（不发登录包），持续回心跳 → 长停留不断连（报告 §5.3-4）
                for (int i = 0; i < 4; i++) {
                    InPacket ping = readPacket(in, recv);
                    assertThat(ping.readUnsignedShort()).isEqualTo(0x11);
                    ByteArrayOutPacket pong = new ByteArrayOutPacket();
                    pong.writeShort(RecvOpcode.PONG.getValue());
                    send(out, send, pong);
                }
                assertThat(socket.isClosed()).isFalse();
            }
        } finally {
            server.close();
        }
    }

    // ---- helpers（与 NettyIntegrationTest 一致）----

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
