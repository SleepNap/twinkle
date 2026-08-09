package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.event.EventBus;
import org.gms.event.InProcessEventBus;
import org.gms.net.netty.internal.AdminRpcDispatcher;
import org.gms.net.netty.internal.ChannelConnectionRegistry;
import org.gms.net.netty.internal.CoordinatorFrameRouter;
import org.gms.net.netty.internal.CoordinatorLink;
import org.gms.net.netty.internal.DefaultInternalFrame;
import org.gms.net.netty.internal.InternalConnection;
import org.gms.net.netty.internal.InternalFrame;
import org.gms.net.netty.internal.InternalProtocol;
import org.gms.net.netty.internal.InternalServer;
import org.gms.net.netty.internal.JsonCodec;
import org.gms.net.netty.internal.RemoteAdminService;
import org.gms.net.netty.internal.RemoteEventBus;
import org.gms.net.netty.internal.RemoteIntercoordService;
import org.gms.role.SplitChannelCondition;
import org.gms.role.SplitCoordinatorCondition;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.IntercoordService;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * split 档装配（架构 4.5 内部通信：星形拓扑，coordinator 是中心路由器）。
 *
 * <p>按 {@code twinkle.role} 二选一装配：
 * <ul>
 *   <li><b>coordinator（管理进程）</b>：InternalServer 监听 + CoordinatorFrameRouter
 *       （注册中心 + 消息路由 + RPC 真值分发，进程内 IntercoordService 真值）+ RemoteAdminService
 *       （http-api/ai 经它 RPC 到频道）。</li>
 *   <li><b>channel（频道进程）</b>：CoordinatorLink 连 coordinator + RemoteEventBus
 *       （本地派发 + 网络转发）+ RemoteIntercoordService（IntercoordService 网络桩）+ 启动上报
 *       REGISTER 身份。</li>
 * </ul>
 *
 * <p>single 档（role 缺省）不装配本类：CoordinatorConfig 提供进程内真值，频道侧全部进程内，
 * 不启内部通信（现状语义完全保留，铁律 1：同一套代码配置切换）。
 */
@Factory
@Log4j2
public class SplitConfig {



    // ==================== coordinator 角色（管理进程） ====================

    /** 内部连接注册表（channel → 连接 + admin 连接）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitCoordinatorCondition.class)
    public ChannelConnectionRegistry channelConnectionRegistry() {
        return new ChannelConnectionRegistry();
    }

    /** 帧路由器（注册中心 + EVENT 路由 + RPC 真值分发）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitCoordinatorCondition.class)
    public CoordinatorFrameRouter coordinatorFrameRouter(ChannelConnectionRegistry registry,
                                                        IntercoordService intercoordService,
                                                        InProcessEventBus eventBus) {
        return new CoordinatorFrameRouter(registry, intercoordService, eventBus);
    }

    /** 管理进程 AdminService 网络桩（http-api/ai 经它 RPC 到频道进程）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitCoordinatorCondition.class)
    @Primary
    public AdminService adminService(CoordinatorLink coordinatorLink,
                                     @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId) {
        return new RemoteAdminService(coordinatorLink, channelId);
    }

    /** coordinator 内部通信服务端（@Context 强制装配：构造期即启动；@Bean preDestroy 优雅关闭释放端口）。 */
    @Bean(preDestroy = "close")
    @Context
    @Singleton
    @Requires(condition = SplitCoordinatorCondition.class)
    public InternalServer internalServer(CoordinatorFrameRouter router,
                                         @Property(name = "twinkle.coordinator.port", defaultValue = "8510") int port) {
        InternalServer server = new InternalServer(router.connectionHandler());
        server.start(port);
        log.info("coordinator 内部通信已启动，监听端口: {}", port);
        return server;
    }

    /** 管理进程 → coordinator 的连接（RemoteAdminService 用它发 RPC）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitCoordinatorCondition.class)
    public CoordinatorLink managementCoordinatorLink(
            @Property(name = "twinkle.coordinator.host", defaultValue = "127.0.0.1") String host,
            @Property(name = "twinkle.coordinator.port", defaultValue = "8510") int port) {
        CoordinatorLink link = new CoordinatorLink(new InetSocketAddress(host, port), 1000);
        link.addConnectListener(conn -> conn.send(new DefaultInternalFrame(InternalFrame.MessageType.REGISTER,
                conn.nextMessageId(), JsonCodec.encode(
                new InternalProtocol.RegisterPayload(0, host, 0, true, 0)))));
        link.start();
        return link;
    }

    // ==================== channel 角色（频道进程） ====================

    /** 频道进程 → coordinator 的连接（含启动上报 REGISTER 身份 + 重连自动重报）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitChannelCondition.class)
    public CoordinatorLink channelCoordinatorLink(
            @Property(name = "twinkle.coordinator.host", defaultValue = "127.0.0.1") String host,
            @Property(name = "twinkle.coordinator.port", defaultValue = "8510") int port,
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            @Property(name = "twinkle.net.channel.host", defaultValue = "127.0.0.1") String channelHost,
            @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int channelPort) {
        CoordinatorLink link = new CoordinatorLink(new InetSocketAddress(host, port), 1000);
        // 连接建立（含重连）→ 上报频道身份（注册中心，架构 4.6.4）
        link.addConnectListener(conn -> {
            conn.send(new DefaultInternalFrame(InternalFrame.MessageType.REGISTER,
                    conn.nextMessageId(), JsonCodec.encode(
                    new InternalProtocol.RegisterPayload(channelId, channelHost, channelPort, false, 0))));
            log.info("频道 {} 已上报 coordinator: {}:{}", channelId, channelHost, channelPort);
        });
        link.start();
        return link;
    }

    /** 频道进程 EventBus = RemoteEventBus（本地派发 + 网络转发，@Primary 覆盖 InProcessEventBus）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitChannelCondition.class)
    @Primary
    public EventBus eventBus(InProcessEventBus local, CoordinatorLink channelCoordinatorLink) {
        return new RemoteEventBus(local, channelCoordinatorLink);
    }

    /** 频道进程 IntercoordService = RemoteIntercoordService（RPC 到 coordinator 真值）。 */
    @Bean
    @Singleton
    @Requires(condition = SplitChannelCondition.class)
    @Primary
    public IntercoordService intercoordService(CoordinatorLink channelCoordinatorLink) {
        return new RemoteIntercoordService(channelCoordinatorLink);
    }

    /** 频道进程 AdminService RPC 处理（管理进程经 coordinator 转来的运维 RPC）。 */
    @Bean
    @Context
    @Singleton
    @Requires(condition = SplitChannelCondition.class)
    public ChannelAdminRpcBinder channelAdminRpcBinder(CoordinatorLink channelCoordinatorLink,
                                                       AdminService adminService) {
        return new ChannelAdminRpcBinder(channelCoordinatorLink, adminService);
    }

    // ==================== 启动装配 ====================

    /** 频道进程挂接 AdminService RPC 分发（管理进程运维操作落到频道真值）。 */
    @Singleton
    public static final class ChannelAdminRpcBinder {
        public ChannelAdminRpcBinder(CoordinatorLink link, AdminService adminService) {
            AdminRpcDispatcher dispatcher = new AdminRpcDispatcher(adminService);
            link.addConnectListener(conn -> conn.onRpcRequest(env ->
                    conn.replyRpc(env.messageId(), dispatcher.dispatch(env.request().method(), env.request().args()))));
        }
    }
}
