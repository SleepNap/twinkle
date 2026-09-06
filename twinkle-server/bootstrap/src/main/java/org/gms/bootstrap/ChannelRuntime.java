package org.gms.bootstrap;

import org.gms.channel.ChannelChangeReceiver;
import org.gms.channel.ChannelLocationBinder;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.ChannelMessageSubscriber;
import org.gms.channel.ChannelServer;
import org.gms.channel.MonsterReassignTickHandler;
import org.gms.channel.MonsterSpawnService;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.admin.ChannelAdminService;
import org.gms.domain.game.lease.DefaultControllerLeaseService;
import org.gms.net.packet.HandlerRegistry;
import org.gms.service.admin.AdminService;
import org.gms.tick.TickScheduler;
import org.gms.concurrent.GameExecution;

import java.util.List;

/**
 * 单个频道的隔离运行态。它拥有频道私有的监听器、玩家/地图状态、tick handler 和事件订阅，
 * 因而可以独立关闭并完整回收；WZ、脚本、存档队列等进程级资源不属于这里。
 */
public final class ChannelRuntime implements AutoCloseable {

    private final ChannelWorkerSpec.Endpoint endpoint;
    private final HandlerRegistry handlers;
    private final ChannelMapManager maps;
    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final DefaultControllerLeaseService leases;
    private final MonsterSpawnService monsters;
    private final MonsterReassignTickHandler reassign;
    private final ChannelServer server;
    private final TickScheduler tickScheduler;
    private final List<AutoCloseable> subscriptions;
    private ChannelAdminService admin;
    private final GameExecution execution;

    public ChannelRuntime(ChannelWorkerSpec.Endpoint endpoint, HandlerRegistry handlers,
                   ChannelMapManager maps, PlayerStorage players, PlayerSessionRegistry sessions,
                   DefaultControllerLeaseService leases, MonsterSpawnService monsters,
                   MonsterReassignTickHandler reassign, ChannelServer server,
                   TickScheduler tickScheduler, ChannelMessageSubscriber messages,
                   ChannelChangeReceiver changes, ChannelLocationBinder locations, AutoCloseable gameplay,
                   GameExecution execution) {
        this.endpoint = endpoint;
        this.handlers = handlers;
        this.maps = maps;
        this.players = players;
        this.sessions = sessions;
        this.leases = leases;
        this.monsters = monsters;
        this.reassign = reassign;
        this.server = server;
        this.tickScheduler = tickScheduler;
        this.subscriptions = List.of(messages, changes, locations, gameplay);
        this.execution = execution;
    }

    public int channelId() { return endpoint.channelId(); }
    public String host() { return endpoint.host(); }
    public int port() { return endpoint.port(); }
    public ChannelServer server() { return server; }
    public PlayerStorage players() { return players; }
    public PlayerSessionRegistry sessions() { return sessions; }
    public ChannelMapManager maps() { return maps; }
    public AdminService admin() { return admin; }
    public HandlerRegistry handlers() { return handlers; }
    public void drainStateTasks() { execution.run(() -> { }); }

    public void admin(ChannelAdminService admin) {
        this.admin = admin;
    }

    @Override
    public void close() {
        server.stop();
        execution.run(() -> subscriptions.reversed().forEach(ChannelRuntime::closeQuietly));
        monsters.close();
        tickScheduler.unregister(leases);
        tickScheduler.unregister(reassign);
        execution.close();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭其它资源比单个订阅取消失败更重要；各订阅实现已记录具体错误。
        }
    }
}
