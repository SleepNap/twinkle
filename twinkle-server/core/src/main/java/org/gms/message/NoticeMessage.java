package org.gms.message;

/**
 * 喇叭/活动公告消息（架构 4.4 消息总线：广播 = 喇叭/公告，coordinator 群发所有频道）。
 *
 * @param channelId 来源频道（0 = 管理/活动侧发起）
 * @param content   公告内容
 * @param itemId    喇叭道具 id（0 = 普通公告/活动）
 */
public record NoticeMessage(int channelId, String content, int itemId) {
}
