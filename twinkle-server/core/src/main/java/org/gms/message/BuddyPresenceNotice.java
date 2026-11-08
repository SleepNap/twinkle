package org.gms.message;

/**
 * 跨频道好友列表刷新通知（架构 4.4 消息总线：投递好友属主频道）。
 *
 * <p>登录、断线、接受或删除好友后，变化者所在频道无法直接向其它频道的玩家回包；由本通知
 * 交给对方属主频道的 {@code BuddyHandler} 重建整表并下发。{@code peerChannel} 显式携带变化者
 * 的当前频道（-1 表示已离线），避免 Presence 注销尚未完成时读到旧在线状态。
 *
 * @param ownerId     需要刷新好友列表的玩家（本通知的接收频道上在线）
 * @param peerId      发生变化的好友角色 id
 * @param peerChannel 变化者当前频道；-1 表示离线
 */
public record BuddyPresenceNotice(long ownerId, long peerId, int peerChannel) {
}
