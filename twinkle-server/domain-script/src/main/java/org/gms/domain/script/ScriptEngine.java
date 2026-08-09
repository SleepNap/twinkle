package org.gms.domain.script;

import lombok.extern.log4j.Log4j2;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.Map;

/**
 * GraalVM JS 脚本引擎（架构 2.1 / 红线 16：独立库引入，配 EnableJVMCI 全速）。
 *
 * <p>宿主对象契约（cm/qm/em/rm/im，架构 M0 9 项）接口化——脚本通过
 * {@link ScriptHost} 接口访问宿主，脚本逻辑不直接触碰游戏内存对象（红线 11/12）。
 *
 * <p>M0 阶段验证：引擎能启动、能执行 JS、宿主对象能传参调用。M2 接入 NPC/任务/事件脚本。
 *
 * <h2>2C2G 预算</h2>
 * <p>GraalVM 引擎启动约 150-250ms（首次），常驻原生内存约 60-100M（见 ARCHITECTURE.md 9.1：
 * 原生 512M 预算内含 GraalVM JS 引擎）。context 复用单例，避免每次创建。
 */
@Log4j2
public final class ScriptEngine {



    private final Context context;

    public ScriptEngine() {
        // 只启用 JS 语言（不启用 python 等其他语言，省内存）。
        // polyglot 全局可见性：false → JS 只能看到显式传入的宿主对象。
        this.context = Context.newBuilder("js")
                .allowAllAccess(true)          // M0 演示用；M2 收紧为 allowHostAccess(CONFIGURE)
                .allowHostAccess(HostAccess.ALL) // 显式允许宿主对象方法调用（匿名类成员方法需此）
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false") // 避免 interpreter-only 警告
                .build();
    }

    /**
     * 执行 JS 表达式 / 语句，返回最后一个表达式值。
     *
     * @param script  JS 源码（含函数定义则返回 undefined）
     * @param bindings 注入宿主对象（如 {@code Map.of("cm", hostObj)}）
     */
    public Value eval(String script, Map<String, Object> bindings) {
        bindings.forEach(context.getBindings("js")::putMember);
        Value result = context.eval(Source.create("js", script));
        // 移除注入的绑定，避免跨调用泄漏
        bindings.keySet().forEach(context.getBindings("js")::removeMember);
        return result;
    }

    /**
     * 执行脚本并返回字符串（调试/简单场景）。
     */
    public String evalString(String script) {
        return eval(script, Map.of()).asString();
    }

    /**
     * 关闭引擎（释放原生资源）。
     */
    public void close() {
        try {
            context.close();
        } catch (Exception e) {
            log.warn("关闭 GraalVM 上下文异常", e);
        }
    }
}
