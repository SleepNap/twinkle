package org.gms.message;

/**
 * 消息总线 payload 集合（架构 4.4 三机制之一"消息总线"）。
 *
 * <p>总线不存状态，只负责送达（悄悄话=发目标频道、喇叭=广播、CC 请求=发目标频道）。
 * target 为逻辑名（{@code channel:{id}} 精确投递或 {@code *} 广播），由实现（进程内 EventBus /
 * M6 网络帧）落到本地调用或网络传输。
 */
public final class MessageTargets {

    /** 广播目标（EventBus 通配）。 */
    public static final String BROADCAST = "*";

    /** 频道精确目标前缀（如 {@code channel:1}）。 */
    public static String channel(int channelId) {
        return "channel:" + channelId;
    }

    private MessageTargets() {
    }
}
