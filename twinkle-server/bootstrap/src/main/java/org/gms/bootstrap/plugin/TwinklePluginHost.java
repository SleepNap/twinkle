package org.gms.bootstrap.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.event.EventBus;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.LogicSystemRegistry;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.PacketHandler;
import org.gms.plugin.ContributionHandle;
import org.gms.plugin.ContributionType;
import org.gms.plugin.PluginContext;
import org.gms.plugin.PluginDescriptor;
import org.gms.plugin.PluginHost;
import org.gms.tick.TickHandler;
import org.gms.tick.TickScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 插件宿主实现（架构 7.2：平台只暴露贡献点，宿主把声明式/命令式贡献点落进各注册表）。
 *
 * <p>放在 bootstrap（装配层）：宿主需要看见 HandlerRegistry / TickScheduler / LogicSystemRegistry /
 * EventBus 等注册表，core 看不到这些。按 descriptor 的贡献点用插件的 {@code classLoader} 实例化贡献类
 * 并登记，逐贡献点返回回滚句柄（unload 时统一 close）。
 *
 * <p>贡献点版本（红线 13）：声明式贡献点用 manifest 版本经 {@link VersionGate} 换代兜底——
 * {@code max(声明版本, versionGate.currentVersion())}，保证 reload 后 replace 单调递增。
 *
 * <p>命令式贡献点路由（{@link PluginContext#contributions()}）：支持 tick-handler 与事件订阅；
 * 包处理器必须走 manifest 声明（opcode 需在 manifest 中给出）。
 */
public final class TwinklePluginHost implements PluginHost {

    private static final Logger LOG = LogManager.getLogger(TwinklePluginHost.class);

    private final HandlerRegistry packetRegistry;
    private final LogicSystemRegistry logicSystemRegistry;
    private final TickScheduler tickScheduler;
    private final EventBus eventBus;
    private final VersionGate versionGate;
    private final EntityReloadCoordinator entityReloadCoordinator;

    public TwinklePluginHost(HandlerRegistry packetRegistry,
                             LogicSystemRegistry logicSystemRegistry,
                             TickScheduler tickScheduler,
                             EventBus eventBus,
                             VersionGate versionGate,
                             EntityReloadCoordinator entityReloadCoordinator) {
        this.packetRegistry = packetRegistry;
        this.logicSystemRegistry = logicSystemRegistry;
        this.tickScheduler = tickScheduler;
        this.eventBus = eventBus;
        this.versionGate = versionGate;
        this.entityReloadCoordinator = entityReloadCoordinator;
    }

    @Override
    public List<ContributionHandle> applyContributions(PluginDescriptor descriptor, PluginContext context) {
        List<ContributionHandle> handles = new ArrayList<>();
        ClassLoader loader = context.classLoader();

        // ---- 包处理器贡献点 → HandlerRegistry ----
        for (PluginDescriptor.PacketHandlerContribution c : descriptor.packetHandlers()) {
            try {
                PacketHandler handler = instantiate(c.className(), PacketHandler.class, loader, descriptor.id());
                RecvOpcode opcode = RecvOpcode.valueOf(c.opcode());
                int version = maxVersion(c.version());
                if (packetRegistry.find(opcode.getValue()).isPresent()) {
                    packetRegistry.replace(opcode, handler, version);
                } else {
                    packetRegistry.register(opcode, handler, version);
                }
                handles.add(() -> packetRegistry.unregister(opcode));
                LOG.info("插件贡献点注册: [{}] packet-handler {}（v{}）", descriptor.id(), opcode, version);
            } catch (RuntimeException e) {
                LOG.error("插件包处理器注册失败: [{}] opcode={}", descriptor.id(), c.opcode(), e);
            }
        }

        // ---- tick 任务贡献点 → TickScheduler ----
        for (PluginDescriptor.TickHandlerContribution c : descriptor.tickHandlers()) {
            try {
                TickHandler handler = instantiate(c.className(), TickHandler.class, loader, descriptor.id());
                tickScheduler.register(handler);
                handles.add(() -> tickScheduler.unregister(handler));
                LOG.info("插件贡献点注册: [{}] tick-handler {}（v{}）", descriptor.id(), c.className(), c.version());
            } catch (RuntimeException e) {
                LOG.error("插件 tick 任务注册失败: [{}] {}", descriptor.id(), c.className(), e);
            }
        }

        // ---- 事件监听贡献点 → EventBus（经 context 命令式门面订阅，实例化一次 + 常驻实例） ----
        for (PluginDescriptor.EventListenerContribution c : descriptor.eventListeners()) {
            try {
                Class<?> eventClass = Class.forName(c.eventClassName(), true, loader);
                Object listener = instantiate(c.className(), Object.class, loader, descriptor.id());
                Consumer<Object> consumer = event -> {
                    try {
                        listener.getClass().getMethod("onEvent", eventClass).invoke(listener, eventClass.cast(event));
                    } catch (ReflectiveOperationException e) {
                        LOG.error("插件事件监听执行异常: [{}] {}", descriptor.id(), c.className(), e);
                    }
                };
                handles.add(subscribe(context, c.target(), eventClass, consumer));
                LOG.info("插件贡献点注册: [{}] event-listener {}@{}（v{}）", descriptor.id(), c.className(), c.target(), c.version());
            } catch (ClassNotFoundException | RuntimeException e) {
                LOG.error("插件事件监听注册失败: [{}] {}", descriptor.id(), c.className(), e);
            }
        }

        // ---- 逻辑系统贡献点 → LogicSystemRegistry ----
        for (PluginDescriptor.LogicSystemContribution c : descriptor.logicSystems()) {
            try {
                Object system = instantiate(c.className(), Object.class, loader, descriptor.id());
                int version = maxVersion(c.version());
                if (logicSystemRegistry.find(c.key()).isPresent()) {
                    logicSystemRegistry.replace(c.key(), system, version);
                } else {
                    logicSystemRegistry.register(c.key(), system, version);
                }
                handles.add(() -> logicSystemRegistry.unregister(c.key()));
                LOG.info("插件贡献点注册: [{}] logic-system {}（v{}）", descriptor.id(), c.key(), version);
            } catch (RuntimeException e) {
                LOG.error("插件逻辑系统注册失败: [{}] key={}", descriptor.id(), c.key(), e);
            }
        }

        // ---- 未接线类型（M4 决策）：AI Tool / HTTP 路由 ----
        if (!descriptor.aiTools().isEmpty()) {
            LOG.warn("插件 [{}] 声明 {} 个 AI 工具贡献点，M4 未接线（M5 随管理进程插件宿主一并做）", descriptor.id(), descriptor.aiTools().size());
        }
        if (!descriptor.httpEndpoints().isEmpty()) {
            LOG.warn("插件 [{}] 声明 {} 个 HTTP 路由贡献点，M4 未接线（M5 评估轻量注册表）", descriptor.id(), descriptor.httpEndpoints().size());
        }
        return List.copyOf(handles);
    }

    /**
     * 命令式贡献点路由（插件 {@code PluginContext.contributions().register(...)} 的回调）。
     *
     * <p>支持 tick-handler（tick 贡献点无需额外键）；包处理器必须走 manifest（opcode 需声明）。
     */
    public <T> ContributionHandle registerCommand(String contributionType, T contribution, int version) {
        ContributionType type;
        try {
            type = ContributionType.fromCode(contributionType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("命令式注册不支持的贡献点类型: " + contributionType, e);
        }
        int v = maxVersion(version);
        if (type == ContributionType.TICK_HANDLER) {
            TickHandler th = (TickHandler) contribution;
            tickScheduler.register(th);
            return () -> tickScheduler.unregister(th);
        }
        throw new IllegalArgumentException("命令式注册暂只支持 tick-handler（包处理器需经 manifest 声明 opcode）: " + contributionType);
    }

    /** 命令式事件订阅路由（插件 {@code contributions().subscribe(...)}，直接落 EventBus）。 */
    public <T> ContributionHandle subscribeCommand(String target, Class<T> eventType, Consumer<T> consumer) {
        AutoCloseable sub = eventBus.subscribe(target, eventType, consumer);
        return () -> {
            try {
                sub.close();
            } catch (Exception e) {
                LOG.warn("插件事件退订异常: target={}", target, e);
            }
        };
    }

    /** 贡献点版本 = max(声明/命令式版本, 版本门当前版本)（reload 换代后保证单调递增，红线 13）。 */
    private int maxVersion(int declared) {
        long gateVersion = versionGate.currentVersion();
        return (int) Math.max(declared, gateVersion);
    }

    /** 泛型桥：让 eventClass 的 wildcard 与 Consumer<Object> 兼容（声明式 event-listener 用）。 */
    @SuppressWarnings("unchecked")
    private ContributionHandle subscribe(PluginContext context, String target, Class<?> eventClass, Consumer<Object> consumer) {
        return context.contributions().subscribe(target, (Class) eventClass, consumer);
    }

    @SuppressWarnings("unchecked")
    private <T> T instantiate(String className, Class<T> type, ClassLoader loader, String pluginId) {
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            return (T) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("插件贡献类实例化失败: [%s] %s（期望 %s）".formatted(pluginId, className, type.getName()), e);
        }
    }
}
