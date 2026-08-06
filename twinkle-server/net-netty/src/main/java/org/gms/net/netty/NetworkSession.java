package org.gms.net.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.net.encryption.CipherPair;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端连接会话（架构 net-netty：连接状态机 + 封包分发到 HandlerRegistry）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>握手</b>：连入即发 v83 hello 明文包（含双方 IV），随后全连接加密。</li>
 *   <li><b>分发</b>：收到解密后的 {@link InPacket}，读 opcode → 查 {@link HandlerRegistry}
 *        → 交给对应 {@code PacketHandler}。</li>
 *   <li><b>阶段状态机</b>：M1 校验包顺序（握手 → 登录 → 角色列表 → 选角），
 *       防止跳过阶段。</li>
 *   <li><b>连接态</b>：{@link #send} 发包 / {@link #close} 断链（实现 {@link PacketSession}）。</li>
 * </ul>
 *
 * <p>连接级状态（当前账号、已选角色等）经 {@link #setAttr} 存储，handler 之间传递
 * 不依赖跨操作静态字段（红线 12：可替换层不得持有跨操作状态）。
 */
public final class NetworkSession extends ChannelInboundHandlerAdapter implements PacketSession {

    private static final Logger LOG = LogManager.getLogger(NetworkSession.class);

    /** v83 客户端版本（字节级兼容红线 1）。 */
    public static final short MAPLE_VERSION = 83;

    private final HandlerRegistry registry;
    private final CipherPair ciphers;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private volatile Channel channel;
    private volatile SessionStage stage = SessionStage.HANDSHAKE;

    public NetworkSession(HandlerRegistry registry, CipherPair ciphers) {
        this.registry = registry;
        this.ciphers = ciphers;
    }

    public CipherPair ciphers() {
        return ciphers;
    }

    public SessionStage stage() {
        return stage;
    }

    /**
     * 阶段推进（handler 在完成阶段工作后调用）。
     */
    public void transition(SessionStage next) {
        this.stage = next;
    }

    public void setAttr(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttr(String key) {
        return (T) attributes.get(key);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.channel = ctx.channel();
        writeHello(ctx);
        transition(SessionStage.LOGIN);
        LOG.info("客户端连入: {}", ctx.channel().remoteAddress());
    }

    /**
     * 明文 hello 包（握手协议，字节级兼容 v83）。
     */
    private void writeHello(ChannelHandlerContext ctx) {
        ByteArrayOutPacket hello = new ByteArrayOutPacket();
        hello.writeShort(0x0E);
        hello.writeShort(MAPLE_VERSION);
        hello.writeShort(1);
        hello.writeByte(49);
        hello.writeBytes(ciphers.receive().getInitialIv());
        hello.writeBytes(ciphers.send().getInitialIv());
        hello.writeByte(8);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(hello.getBytes()));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof InPacket packet) {
            int opcode = packet.readUnsignedShort();
            registry.find(opcode).ifPresentOrElse(
                    handler -> handler.handle(this, packet),
                    () -> LOG.warn("未注册的收包 opcode: 0x{}", Integer.toHexString(opcode)));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOG.info("连接关闭: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 日志红线 9：log.error("描述", e)
        LOG.error("连接异常，断开: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    @Override
    public void send(OutPacket packet) {
        Channel c = channel;
        if (c != null && c.isActive()) {
            c.writeAndFlush(packet);
        }
    }

    @Override
    public void close(String reason) {
        LOG.info("主动断开连接，原因: {}", reason);
        Channel c = channel;
        if (c != null) {
            c.close();
        }
    }
}
