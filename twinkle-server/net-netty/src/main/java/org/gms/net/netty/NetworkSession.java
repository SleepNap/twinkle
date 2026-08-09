package org.gms.net.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.log4j.Log4j2;
import org.gms.net.encryption.CipherPair;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端连接会话（架构 net-netty：连接状态机 + 封包分发到 HandlerRegistry）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>握手</b>：连入即发 v83 hello 明文包（含双方 IV），随后全连接加密。</li>
 *   <li><b>分发</b>：收到解密后的 {@link InPacket}，读 opcode → 查 {@link HandlerRegistry}
 *        → 交给对应 {@code PacketHandler}。PONG 在分发前拦截（传输心跳，各阶段共享）。</li>
 *   <li><b>心跳</b>：readerIdle → PING → PONG deadline 状态机（事故报告阶段 B：分阶段
 *        心跳替代 allIdle；探测超时才关闭，长停留不因业务阶段被误伤）。</li>
 *   <li><b>阶段状态机</b>：M1 校验包顺序（握手 → 登录 → 角色列表 → 选角），
 *        防止跳过阶段。</li>
 *   <li><b>连接态</b>：{@link #send} 发包 / {@link #close} 断链（实现 {@link PacketSession}）。</li>
 * </ul>
 *
 * <p>连接级状态（当前账号、已选角色等）经 {@link #setAttr} 存储，handler 之间传递
 * 不依赖跨操作静态字段（红线 12：可替换层不得持有跨操作状态）。
 */
@Log4j2
public final class NetworkSession extends ChannelInboundHandlerAdapter implements PacketSession {

    /** 会话 id 分配（全局单调；同一 TCP 连接创建即得、全程不变，事故报告阶段 B）。 */
    private static final AtomicLong SESSION_ID_SEQ = new AtomicLong();

    private final long sessionId = SESSION_ID_SEQ.incrementAndGet();
    private final HandlerRegistry registry;
    private final CipherPair ciphers;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final DisconnectListener disconnectListener;
    private final HeartbeatGuard heartbeat;

    private volatile Channel channel;
    private volatile SessionStage stage = SessionStage.HANDSHAKE;

    public NetworkSession(HandlerRegistry registry, CipherPair ciphers) {
        this(registry, ciphers, null, HeartbeatConfig.defaults());
    }

    public NetworkSession(HandlerRegistry registry, CipherPair ciphers, DisconnectListener disconnectListener) {
        this(registry, ciphers, disconnectListener, HeartbeatConfig.defaults());
    }

    public NetworkSession(HandlerRegistry registry, CipherPair ciphers, DisconnectListener disconnectListener,
                          HeartbeatConfig heartbeatConfig) {
        this.registry = registry;
        this.ciphers = ciphers;
        this.disconnectListener = disconnectListener;
        this.heartbeat = new HeartbeatGuard(heartbeatConfig);
    }

    @Override
    public long sessionId() {
        return sessionId;
    }

    /** 心跳观测快照（测试/运维读，报告 §七 心跳指标；返回类型 public，红线 12 合法）。 */
    public HeartbeatGuard.HeartbeatStats heartbeatStats() {
        return heartbeat.stats();
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
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
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
        log.info("客户端连入: {}", ctx.channel().remoteAddress());
    }

    /**
     * 明文 hello 包（握手协议，字节级兼容 v83）。
     */
    private void writeHello(ChannelHandlerContext ctx) {
        ByteArrayOutPacket hello = new ByteArrayOutPacket();
        hello.writeShort(0x0E);
        // 版本号直接取本连接加密器持有的版本（CipherPair 由 PacketSession.MAPLE_VERSION 构造），
        // 与加密 header 的版本 key 恒一致——杜绝 hello 与加密版本分叉。
        hello.writeShort(ciphers.mapleVersion());
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
            if (opcode == RecvOpcode.PONG.getValue()) {
                // 传输心跳响应：PONG 在分发前拦截，不进 HandlerRegistry（各阶段共享稳定贡献点）
                heartbeat.onInboundPacket(stage(), true);
                return;
            }
            heartbeat.onInboundPacket(stage(), false);
            registry.find(opcode).ifPresentOrElse(
                    handler -> handler.handle(this, packet),
                    () -> log.warn("未注册的收包 opcode: 0x{}", Integer.toHexString(opcode)));
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent ise && ise.state() == IdleState.READER_IDLE) {
            // readerIdle：发 PING 探测；PROBING 中已超期限则关闭（不因业务阶段长停留误伤）
            heartbeat.onReaderIdle(stage(), System.nanoTime(), this::sendPing, () -> ctx.close());
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /** 构造 PING 帧并发出（v83：short 0x11 + short seq，真实客户端忽略负载回空 PONG）。 */
    private void sendPing(long seq) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.PING.getValue());
        p.writeShort((int) seq);
        send(p);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("连接关闭: {}", ctx.channel().remoteAddress());
        DisconnectListener listener = disconnectListener;
        if (listener != null) {
            listener.onDisconnect(this);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 日志红线 9：log.error("描述", e)
        log.error("连接异常，断开: {}", ctx.channel().remoteAddress(), cause);
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
        log.info("主动断开连接，原因: {}", reason);
        Channel c = channel;
        if (c != null) {
            c.close();
        }
    }
}
