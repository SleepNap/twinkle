package org.gms.net.netty.internal;

import java.time.Duration;

/**
 * 心跳接口（架构 4.6.4：注册中心 = coordinator 内建，channel 心跳维护定位表）。
 *
 * <p>M1 只定义契约：channel → coordinator 单向上报 + 心跳；coordinator 心跳超时标记下线。
 * 实现细节（周期、超时阈值、上报内容）M6 随网络总线落地。
 *
 * <p>「coordinator 无状态 + 可重建」由心跳支撑：频道进程崩了 → coordinator 心跳超时标记
 * 下线 → 频道重连重新上报 → 定位表自动重建（架构 4.2）。
 */
public interface Heartbeat {

    /**
     * 心跳周期。
     */
    Duration interval();

    /**
     * 对端心跳超时（超过则判定离线）。
     */
    Duration timeout();

    /**
     * 发送一次心跳。
     */
    void ping();

    /**
     * 对端心跳超时回调（标记下线 / 触发重连）。
     */
    void onTimeout();
}
