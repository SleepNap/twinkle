package org.gms.domain.script.host;

/**
 * 宿主对象契约 em（event manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code em} 触发服务端事件（地图事件、剧情触发、广播等）。
 * v83 脚本兼容约定。
 */
public interface Em {

    /** 设置地图的限时事件（秒；0 = 取消）。 */
    void setMapTimer(int seconds);

    /** 向当前地图广播消息（v83 蓝字/粉字频道）。 */
    void broadcastMessage(String message);
}
