package org.gms.message;

/**
 * 好友申请投递（架构 4.4 消息总线：目标在其它频道时投递目标频道）。
 *
 * <p>申请方频道无法直接给异频道的目标回包，改由目标频道的 {@code BuddyHandler}
 * 向本地会话发送 {@code BUDDYLIST} mode 9 申请包。分组由申请方选择后随包携带。
 *
 * @param fromId   申请方角色 id
 * @param fromName 申请方角色名
 * @param toId     目标角色 id
 * @param group    申请方选择的分组
 */
public record BuddyRequest(long fromId, String fromName, long toId, String group) {
}
