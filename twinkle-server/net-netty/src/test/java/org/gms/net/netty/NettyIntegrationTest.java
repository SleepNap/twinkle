package org.gms.net.netty;

import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.InitializationVector;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketCodec;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * net-netty 端到端验证：真实 Netty 服务端 + 简单 Socket 客户端。
 *
 * <p>覆盖：握手（明文 hello 含 IV）→ 客户端加密发包 → 服务端解密 → handler 分发 →
 * 服务端加密回包 → 客户端解密读取。验证编解码与分发全链路字节级兼容（红线 1）。
 */
class NettyIntegrationTest {

    @Test
    void serverCanStopAndBindTheSamePortAgain() throws Exception {
        LoginServer server = new LoginServer(new HandlerRegistry());
        server.start(0);
        int port = server.boundPort();
        try {
            assertThat(readHello(port)).hasSize(16);

            server.stop();
            assertThat(server.isRunning()).isFalse();

            server.start(port);
            assertThat(server.isRunning()).isTrue();
            assertThat(server.boundPort()).isEqualTo(port);
            assertThat(readHello(port)).hasSize(16);
        } finally {
            server.close();
        }
    }

    @Test
    void helloHandshakeAndEncryptedRoundTrip() throws Exception {
        // 服务端：注册一个回包 handler（WHISPER → 回 PING + 字符串）。
        // 注意：不能再用 PONG——NetworkSession 已把 PONG 作为传输心跳在分发前拦截（事故报告阶段 B）。
        HandlerRegistry registry = new HandlerRegistry();
        registry.register(RecvOpcode.WHISPER, (session, packet) -> {
            ByteArrayOutPacket out = new ByteArrayOutPacket();
            out.writeShort(SendOpcode.PING.getValue());
            out.writeString("pong-reply");
            session.send(out);
        });

        LoginServer server = new LoginServer(registry);
        server.start(0);
        try {
            int port = server.boundPort();
            assertThat(port).isGreaterThan(0);

            try (Socket socket = new Socket("127.0.0.1", port);
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {

                // 1. 读明文 hello（16 字节）
                byte[] hello = in.readNBytes(16);
                assertThat(hello.length).isEqualTo(16);
                assertThat(hello[0]).isEqualTo((byte) 0x0E);
                // 版本 83 小端
                assertThat(hello[2]).isEqualTo((byte) 83);
                assertThat(hello[3]).isZero();

                // 2. 解析 IV：hello 布局 [short 0x0E][short 83][short 1][byte 49][recvIv 4][sendIv 4][byte 8]
                byte[] recvIv = Arrays.copyOfRange(hello, 7, 11);
                byte[] sendIv = Arrays.copyOfRange(hello, 11, 15);
                assertThat(recvIv[0]).isEqualTo((byte) 70); // 'F' 接收方向
                assertThat(sendIv[0]).isEqualTo((byte) 82); // 'R' 发送方向

                // 3. 客户端方向：发用 recvIv 加密，收用 sendIv 解密。
                //    接收版本 key 用 0xFFFF-83：真实客户端以 0xFFFF-version 校验服务端
                //    发出的包头（镜像 CipherPair 发送侧），测试须对齐才是一致真值。
                AesCipher clientSend = new AesCipher(InitializationVector.of(recvIv), (short) 83);
                AesCipher clientRecv = new AesCipher(InitializationVector.of(sendIv), (short) (0xFFFF - 83));

                // 4. 发加密 WHISPER（替代 PONG 回环，PONG 已被传输心跳拦截）
                ByteArrayOutPacket pong = new ByteArrayOutPacket();
                pong.writeShort(RecvOpcode.WHISPER.getValue());
                byte[] wire = PacketCodec.encodePacket(clientSend, pong.getBytes());
                out.write(wire);
                out.flush();

                // 5. 收服务端加密回包（拆包解密）
                byte[] headerBytes = in.readNBytes(4);
                int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                        | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
                int length = AesCipher.decodePacketLength(header);
                byte[] body = in.readNBytes(length);
                InPacket reply = PacketCodec.decodePacket(clientRecv, header, body);

                assertThat(reply.readUnsignedShort()).isEqualTo(SendOpcode.PING.getValue());
                assertThat(reply.readString()).isEqualTo("pong-reply");
            }
        } finally {
            server.close();
        }
    }

    private static byte[] readHello(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             DataInputStream in = new DataInputStream(socket.getInputStream())) {
            return in.readNBytes(16);
        }
    }
}
