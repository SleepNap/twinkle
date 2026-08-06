package org.gms.hotreload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 重启协调器状态机（架构 5.4 L4：DRAINING → 增量 FLUSH → 重启 → 恢复）。
 *
 * <p>进程级兜底（必须换进程 / 崩溃 / 泄漏）时的优雅关停编排。三阶段：
 * <ol>
 *   <li><b>DRAINING</b>：排空在途操作（tick 帧边界停、单写执行器排空、在线玩家状态快照），
 *       "只排空在途操作，不做全量落盘"（架构 5.4 路径 B）。</li>
 *   <li><b>FLUSH_DIRTY</b>：增量 FLUSH，只刷脏数据（自上次落盘后变更的），不做全量（红线 17）。</li>
 *   <li><b>RESTARTING</b>：关闭旧 JVM / 准备新进程。</li>
 *   <li><b>RESTORED</b>：新进程恢复（从 DB 加载 + 上下文恢复，玩家"闪一下"回来）。</li>
 * </ol>
 *
 * <p>错误态：任一步抛异常 → {@link #fail(Throwable)} 记录并回退，进程进入错误态（继续运行，等待人工
 * 介入或再次触发重启）。
 *
 * <p>线程模型：{@link #beginRestart(Runnable, Runnable, Runnable)} 是同步编排——调用方（运维命令 / 管理
 * API 线程）阻塞直到完成。若要在后台异步执行，由调用方包 {@code Thread}。不在这里引入线程。
 */
public final class RestartCoordinator {

    private static final Logger LOG = LogManager.getLogger(RestartCoordinator.class);

    /** 重启阶段状态机。 */
    public enum Phase {
        RUNNING,       // 正常运行（初始）
        DRAINING,      // 排空在途操作
        FLUSH_DIRTY,   // 增量 FLUSH
        RESTARTING,    // 关停旧进程
        RESTORED,      // 新进程恢复
        FAILED         // 编排失败（进程继续运行）
    }

    private final CopyOnWriteArrayList<Consumer<Phase>> listeners = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.RUNNING;
    private volatile long drainTimeoutMillis = 30_000;
    private volatile Throwable lastFailure;

    /**
     * 注册阶段监听（监控面板 / 管理 API 用）。
     */
    public AutoCloseable onPhaseChange(Consumer<Phase> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public Phase phase() {
        return phase;
    }

    public void setDrainTimeout(Duration timeout) {
        this.drainTimeoutMillis = timeout.toMillis();
    }

    /**
     * 执行一次完整重启编排（同步）。
     *
     * @param drain  排空在途操作（必须幂等、可重复调用）
     * @param flush  增量 FLUSH 脏数据（必须幂等）
     * @param restart 关停旧进程（System.exit / 换进程脚本），通常不返回
     */
    public void beginRestart(Runnable drain, Runnable flush, Runnable restart) {
        transition(Phase.DRAINING);
        try {
            drain.run();
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        transition(Phase.FLUSH_DIRTY);
        try {
            flush.run();
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        transition(Phase.RESTARTING);
        try {
            restart.run();
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        // restart 通常不返回（System.exit 或 exec）。若返回说明编排方决定进程内恢复，标 RESTORED。
        transition(Phase.RESTORED);
    }

    /**
     * 编排失败：记录并回退到 FAILED（进程继续运行，等待人工 / 再次触发）。
     */
    public void fail(Throwable cause) {
        this.lastFailure = cause;
        transition(Phase.FAILED);
        // 日志红线 9：log.error("描述", e)
        LOG.error("重启编排失败，进程保持运行等待人工介入", cause);
    }

    /** 复位到 RUNNING（恢复成功 / 人工确认后）。 */
    public void reset() {
        this.lastFailure = null;
        transition(Phase.RUNNING);
    }

    public Throwable lastFailure() {
        return lastFailure;
    }

    private void transition(Phase next) {
        Phase prev = phase;
        phase = next;
        LOG.info("RestartCoordinator 状态: {} → {}", prev, next);
        for (Consumer<Phase> l : listeners) {
            try {
                l.accept(next);
            } catch (RuntimeException e) {
                // 监听器异常不影响状态机
                LOG.error("状态监听器异常", e);
            }
        }
    }

    /** 测试辅助：直接强制设阶段。 */
    void forcePhase(Phase p) {
        transition(p);
    }

    /** 测试辅助。 */
    List<String> transitionLog() {
        return new ArrayList<>();
    }
}
