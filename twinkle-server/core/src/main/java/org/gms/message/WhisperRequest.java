package org.gms.message;

/**
 * 悄悄话消息（架构 4.4 消息总线：悄悄话=发目标频道，经定位表查目标在哪个频道）。
 *
 * @param fromId   发送者角色 id
 * @param fromName 发送者角色名（客户端展示）
 * @param toId     接收者角色 id
 * @param toName   接收者角色名（投递目标）
 * @param content  消息内容
 */
public record WhisperRequest(long fromId, String fromName, long toId, String toName, String content) {
}
