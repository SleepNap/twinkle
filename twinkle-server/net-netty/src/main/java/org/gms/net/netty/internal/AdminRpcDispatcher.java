package org.gms.net.netty.internal;

import lombok.extern.log4j.Log4j2;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;

import java.util.Optional;

/**
 * AdminService RPC 分发器（架构 4.6.6 第②路：管理进程 RPC → 频道进程真值）。
 *
 * <p>频道进程侧：管理进程经 coordinator 转发来的 RPC 帧（方法名 = 频道 AdminService 操作），
 * 本类分发到频道进程内 {@link AdminService} 实现（{@code ChannelAdminService}），结果序列化回
 * RPC_RESPONSE。只依赖 core 接口（不依赖 channel 模块具体类，防管理侧依赖游戏内存）。
 *
 * <p>返回值约定同 {@link IntercoordRpcDispatcher}：值 JSON 字符串；{@code void}/{@code null}/
 * {@code Optional.empty} 返回 {@code "null"}。
 */
@Log4j2
public final class AdminRpcDispatcher {



    private final AdminService admin;

    public AdminRpcDispatcher(AdminService admin) {
        this.admin = admin;
    }

    /** 分发 AdminService RPC。 */
    public InternalProtocol.RpcResponse dispatch(String method, String[] args) {
        try {
            return switch (method) {
                case "onlineSummary" -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(admin.onlineSummary()));
                case "kick" -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(admin.kick(longArg(args, 0))));
                case "reloadScripts" -> InternalProtocol.RpcResponse.ok(JsonCodec.encode(admin.reloadScripts()));
                case "requestRestart" -> {
                    admin.requestRestart();
                    yield InternalProtocol.RpcResponse.ok("null");
                }
                case "restartPhase" -> {
                    RestartCoordinator.Phase phase = admin.restartPhase();
                    yield InternalProtocol.RpcResponse.ok(JsonCodec.encode(phase == null ? RestartCoordinator.Phase.RUNNING : phase));
                }
                default -> InternalProtocol.RpcResponse.fail("未知 AdminService RPC 方法: " + method);
            };
        } catch (Exception e) {
            log.error("AdminService RPC 分发失败: method={}", method, e);
            return InternalProtocol.RpcResponse.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long longArg(String[] args, int i) {
        return args == null || i >= args.length ? 0L : JsonCodec.decode(args[i], Long.class.getName());
    }
}
