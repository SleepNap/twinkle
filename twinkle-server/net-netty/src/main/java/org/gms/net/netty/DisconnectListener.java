package org.gms.net.netty;

import org.gms.net.packet.PacketSession;

/**
 * 连接断开回调（架构 net-netty：IO 层不依赖业务，断链清理经此接口回调给装配层）。
 *
 * <p>频道服进图后在连接上登记角色，断链时需从在线表/会话注册表注销——
 * IO 层（NetworkSession）不认识业务对象，经本接口把断开事件抛给装配层处理。
 */
public interface DisconnectListener {

    /** 连接关闭时回调（在 Netty IO 线程上，须快速返回，不要阻塞）。 */
    void onDisconnect(PacketSession session);
}
