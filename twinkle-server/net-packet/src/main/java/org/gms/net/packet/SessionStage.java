package org.gms.net.packet;

/**
 * 连接阶段状态机（v83 登录协议：握手 → 登录 → 角色列表 → 选角 → 进图）。
 *
 * <p>定义在协议层，使 {@link PacketHandler} 与 IO 层（net-netty）共享阶段定义而不互相依赖。
 * M1 到 {@link #SELECTED}；M2 进图新增 {@link #IN_GAME}（频道内）。
 */
public enum SessionStage {
    /** 已连入，等待发握手包。 */
    HANDSHAKE,
    /** 已握手，等待登录包。 */
    LOGIN,
    /** 登录成功，等待服务器列表 / 角色列表请求。 */
    AUTHED,
    /** 已发角色列表，等待选角。 */
    CHARLIST,
    /** 已选中角色（M1 终点）。 */
    SELECTED,
    /** 已在频道内（进图完成，游戏内，M2）。 */
    IN_GAME
}
