package org.gms.event;

import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内 EventBus 实现（函数调用 + 同步派发）。
 *
 * <p>线程模型：发送线程是调用方线程（同步派发）。这是有意为之——游戏 tick 单线程，事件投递发生在
 * tick 切帧边界，订阅者代码天然在 tick 线程上下文执行，省去线程切换。如果订阅者要异步，必须自行
 * 用 {@link java.util.concurrent.Executor} 移交。
 *
 * <p>M6 引入跨进程实现（同接口）后，本类仍然作为单进程 / split 内同进程模块间的默认实现，
 * 跨进程走 {@code RemoteEventBus}（届时落 {@link Singleton} 替换，由 {@code Bootstrap} 选择装配）。
 *
 * <h2>订阅索引</h2>
 * <ul>
 *   <li>{@code target -> type -> List<handler>} 三层索引。订阅者按精确 target 匹配（架构 4.4 三机制的
 *       "消息总线"使用方：悄悄话按目标频道投递，不广播）。</li>
 *   <li>{@link CopyOnWriteArrayList} 配合 {@code AutoCloseable} 取消订阅的 {@code removeIf} 遍历——订阅
 *       增删频次远低于派发，不需要写入优化。</li>
 * </ul>
 */
@Singleton
public final class InProcessEventBus implements EventBus, ReliableDelivery {

    private static final Logger LOG = LogManager.getLogger(InProcessEventBus.class);

    /** 精确目标匹配，未来可能扩展通配（"channel:*"）。 */
    private final ConcurrentMap<String, ConcurrentMap<Class<?>, CopyOnWriteArrayList<HandlerEntry<?>>>> routes = new ConcurrentHashMap<>();

    @Override
    public <T> CompletableFuture<Void> send(String target, T payload) {
        if (payload == null) {
            return CompletableFuture.completedFuture(null);
        }
        dispatch(target, payload);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> void sendReliable(String streamId, long seq, String messageId, String target, T payload) {
        // 进程内可靠投递：直接本地派发（接收侧 ReliableReceiver 用 outbox/bus_stream 判定恰好一次；
        // 序号经 outbox 行传递——InProcessEventBus 不做网络，接收侧从发送方 outbox 读序号）。
        // 本实现无网络转发，携带的序号仅供接收侧 ReliableReceiver 判序（发送方已落 outbox）。
        if (payload != null) {
            dispatch(target, payload);
        }
    }

    private <T> void dispatch(String target, T payload) {
        ConcurrentMap<Class<?>, CopyOnWriteArrayList<HandlerEntry<?>>> byType = routes.get(target);
        if (byType == null) {
            return;
        }
        CopyOnWriteArrayList<HandlerEntry<?>> handlers = byType.get(payload.getClass());
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        // 同步派发：记录异常但不中断其他订阅者。每个订阅者独立 try，避免一处崩了全链路断。
        for (HandlerEntry<?> entry : handlers) {
            dispatch(entry, payload);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler) {
        HandlerEntry<T> entry = new HandlerEntry<>(handler);
        routes.computeIfAbsent(target, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                .add(entry);
        return () -> {
            ConcurrentMap<Class<?>, CopyOnWriteArrayList<HandlerEntry<?>>> byType = routes.get(target);
            if (byType == null) return;
            CopyOnWriteArrayList<HandlerEntry<?>> handlers = byType.get(type);
            if (handlers == null) return;
            handlers.remove(entry);
        };
    }

    /** 调试/观测用：派发量监控。 */
    public int dispatchCount() {
        return routes.values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                .sum();
    }

    @SuppressWarnings("unchecked")
    private <T> void dispatch(HandlerEntry<?> entry, T payload) {
        try {
            ((HandlerEntry<T>) entry).handler.accept(payload);
        } catch (RuntimeException e) {
            // 日志红线 9：log.error("描述", e)，禁用 printStackTrace
            LOG.error("EventBus 订阅者异常: target={}, type={}", entry, payload.getClass().getName(), e);
        }
    }

    private static final class HandlerEntry<T> {
        final Consumer<T> handler;

        HandlerEntry(Consumer<T> handler) {
            this.handler = handler;
        }
    }
}
