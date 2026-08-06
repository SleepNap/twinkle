package org.gms.net.packet;

/**
 * 连接会话最小接口（handler 的对外出口，定义在协议层使 net-packet 零依赖）。
 *
 * <p>IO 层（net-netty）的会话实现本接口。handler 经此发包 / 断链 / 读写连接级状态，
 * 不触碰传输细节（红线 11：可替换层经接口访问稳定层）。
 */
public interface PacketSession {

    /**
     * 发送一个包到对端。
     */
    void send(OutPacket packet);

    /**
     * 主动关闭连接并记录原因。
     */
    void close(String reason);

    /**
     * 当前连接阶段（登录协议状态机 {@link SessionStage}），handler 校验包顺序用。
     */
    SessionStage stage();

    /**
     * 推进连接阶段（handler 完成阶段工作后调用）。
     */
    void transition(SessionStage stage);

    /**
     * 读连接级属性（当前账号、已选角色等，handler 之间传递，不进可替换层静态状态）。
     */
    <T> T getAttr(String key);

    /**
     * 写连接级属性。
     */
    void setAttr(String key, Object value);
}
