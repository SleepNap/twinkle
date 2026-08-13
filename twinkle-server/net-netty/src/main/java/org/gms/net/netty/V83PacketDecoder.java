package org.gms.net.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.net.encryption.AesCipher;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketCodec;

import java.util.List;

/**
 * v83 收包解码器（架构 net-netty：客户端协议栈，字节级兼容红线 1）。
 *
 * <p>从 TCP 流累积拆包：读 4 字节 header → 校验 → 解长度 → 等负载齐 → 解密 → 产出
 * {@link InPacket}。长度异常（负数/超上限）即断开，防篡改攻击。
 *
 * <p>v83 header 4 字节按大端 int 读取（与 {@link AesCipher#isValidHeader} 约定一致）。
 * 握手阶段服务端直发明文 hello，此后客户端所有包均走本解码器（加密）。
 */
@Log4j2
public final class V83PacketDecoder extends ByteToMessageDecoder {


    /** 单包最大负载（防御值：v83 常规包远小于此，超过视为异常/攻击）。 */
    private static final int MAX_PACKET_LENGTH = 0x10000;

    private final AesCipher receiveCipher;

    public V83PacketDecoder(AesCipher receiveCipher) {
        this.receiveCipher = receiveCipher;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        int header = in.readInt();
        if (!receiveCipher.isValidHeader(header)) {
            log.warn(I18n.message("log.packet.invalid_header"), Integer.toHexString(header));
            ctx.close();
            return;
        }
        int length = AesCipher.decodePacketLength(header);
        if (length <= 0 || length > MAX_PACKET_LENGTH) {
            log.warn(I18n.message("log.packet.invalid_length"), length);
            ctx.close();
            return;
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return; // 负载未到齐，等下一次
        }
        byte[] body = new byte[length];
        in.readBytes(body);
        out.add(PacketCodec.decodePacket(receiveCipher, header, body));
    }
}
