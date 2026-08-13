package org.gms.net.packet;

import org.gms.i18n.I18n;
import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.CustomCipher;

/**
 * v83 单包字节级编解码（红线 1：字节级兼容）。
 *
 * <p>数据面约定：
 * <ul>
 *   <li>线包 = {@code [4 字节 header | 加密负载]}。</li>
 *   <li>header 由发送方当前 IV 与长度生成（{@link AesCipher#packetHeader}）；
 *       接收方校验 header（{@link AesCipher#isValidHeader}）并还原长度
 *       （{@link AesCipher#decodePacketLength}）。</li>
 *   <li>负载先做 {@link CustomCipher} 变换，再做 AES-OFB 流式异或；解密为逆序。</li>
 * </ul>
 *
 * <p>本类只处理「单包」字节转换；TCP 流的拆包（累积到完整包）由 IO 层
 * （net-netty）负责，调用本类的单包方法。
 */
public final class PacketCodec {

    private PacketCodec() {
    }

    /**
     * 编码一个包：返回完整线包字节（header + 加密负载）。
     *
     * <p>注意顺序：header 必须先于负载加密计算（负载加密会推进 IV）。
     */
    public static byte[] encodePacket(AesCipher sendCipher, byte[] payload) {
        byte[] header = sendCipher.packetHeader(payload.length);
        byte[] body = payload.clone();
        CustomCipher.encrypt(body);
        sendCipher.crypt(body);
        byte[] out = new byte[4 + body.length];
        System.arraycopy(header, 0, out, 0, 4);
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    /**
     * 解码一个包：校验 header、解密负载、返回可读包。
     *
     * @param receiveCipher 接收方向加密器（当前 IV 须与 header 匹配）
     * @param header        4 字节 header（按小端读入的 int）
     * @param body          加密负载字节（长度 = 还原出的包长）
     */
    public static InPacket decodePacket(AesCipher receiveCipher, int header, byte[] body) {
        if (!receiveCipher.isValidHeader(header)) {
            throw new IllegalStateException(I18n.message("error.packet.header_check_failed"));
        }
        byte[] plain = body.clone();
        receiveCipher.crypt(plain);
        CustomCipher.decrypt(plain);
        return new ByteArrayInPacket(plain);
    }
}
