package org.gms.event;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 进程内 / 跨进程事件总线（同一接口两种实现，架构铁律 1）。
 *
 * <p>设计动机：悄悄话、喇叭、CC 邀请、本地配置变更广播等都是"事件投递"语义，{@code bus.send(target, event)}
 * 不应在进程内 = 函数调用、跨进程 = 网络帧之间分叉。配置中心走的也是这套机制（架构 4.6.5）。
 *
 * <h2>接口契约</h2>
 * <ul>
 *   <li><b>不假设进程方向</b>：target 是逻辑名（角色 / 模块 / 频道 ID），由实现决定怎么落到本地方法
 *       调用还是网络帧。</li>
 *   <li><b>订阅按类型</b>：{@code EventBus<T>} 导出去类型化的订阅；事件载荷（{@code payload}）自身携带数据。</li>
 *   <li><b>失败失败必现</b>：实现内部做失败重传 / 幂等去重（架构 4.5："可靠性在业务层"），但接口面只暴露
 *       是否送达成功的 future，调用方按需 await。</li>
 *   <li><b>同步发送走 tick 线程</b>：游戏 tick 单线程，事件投递发生在 tick 切帧边界；订阅者代码不得阻塞。</li>
 * </ul>
 *
 * <p>M0 落地：进程内实现（后台队列 + 同步派发）。M6 落地网络实现（Netty 帧），接口不变。
 */
public interface EventBus {

    /**
     * 发往逻辑目标（角色 / 频道 ID / 通配）。
     *
     * @param target  逻辑目标。{@code null} 或 {@code "*"} 表示广播到所有订阅者。
     * @param payload 事件载荷（任意类型）
     * @return 投递完成 future。进程内实现立即完成；网络实现可能跨毫秒~秒。
     */
    <T> CompletableFuture<Void> send(String target, T payload);

    /**
     * 订阅目标上的事件。
     *
     * <p>订阅线程上下文：进程内实现在调用线程上同步派发（订阅者切到自己的 tick 线程）；网络实现在
     * Netty IO 线程派发，订阅者须自行移交。
     *
     * @param target   逻辑目标（精确匹配）
     * @param type     事件类型锚（用于按类型过滤）
     * @param handler  事件处理函数；抛异常由实现熔断 + 记日志，不影响其他订阅者
     * @return 订阅句柄，{@link AutoCloseable#close()} 取消订阅
     */
    <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler);
}
