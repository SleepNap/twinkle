package org.gms.message;

/**
 * 好友请求（架构 4.4 消息总线：加好友先经定位表查目标是否在线，投递目标频道）。
 *
 * @param fromId   请求方角色 id
 * @param fromName 请求方角色名
 * @param toId     目标角色 id
 * @param action   动作：请求加好友 / 接受 / 拒绝 / 删除
 */
public record BuddyRequest(long fromId, String fromName, long toId, Action action) {

    public enum Action {
        ADD_REQUEST,   // 请求加好友
        ACCEPT,        // 接受
        REJECT,        // 拒绝
        DELETE         // 删除好友
    }
}
