package org.gms.net.packet;

/**
 * 收包处理器（贡献点，从第一天版本化，红线 13）。
 *
 * <p>注册进 {@link HandlerRegistry}，按 opcode 分发。实现类属于可替换层——
 * 经 {@link PacketSession} 发包、经 InPacket 读字段，不引用游戏对象具体类（红线 11）。
 */
public interface PacketHandler {

    /**
     * 处理一个已解密的收包。
     *
     * @param session 当前连接会话（发包 / 断链出口）
     * @param packet  已解密负载（不含 header）
     */
    void handle(PacketSession session, InPacket packet);
}
