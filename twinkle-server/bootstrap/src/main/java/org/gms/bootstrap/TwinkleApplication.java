package org.gms.bootstrap;

import io.micronaut.runtime.Micronaut;

/**
 * 唯一入口（架构 4.1 / bootstrap）：汇编启动器。
 *
 * <p>任何 {@code java -jar twinkle.jar} 启动都进这里，由 Micronaut 自身完成装配（DI 容器、HTTP、
 * 调度等）。读到的 configurations：
 * <ul>
 *   <li>{@code --profile=...} 命令行参数（被 BootstrapProfile 解析）</li>
 *   <li>{@code twinkle.profile} 配置项（默认 {@code single}）</li>
 *   <li>{@code TWINKLE_PROFILE} 环境变量</li>
 * </ul>
 *
 * <h2>进程装配是配置</h2>
 * <p>同一套代码，按 profile 决定哪些角色模块装配到一个 JVM：
 * <ul>
 *   <li>single / standalone：全部内嵌（coordinator + channel + login + admin + http + ai）</li>
 *   <li>split-channel：管理进程 + 每频道 1 进程（按需启动，profile 在启动脚本中指定）</li>
 *   <li>split-realm：管理进程 + 每大区 1 进程</li>
 * </ul>
 *
 * <h2>2C2G 红线 15</h2>
 * <p>任何 profile 选 split-* 都需要足够的内存预算（每多一个 JVM 常驻 ~150M）。
 * 2C2G 强制单进程——装配层会在配置时拒绝非 single 档。
 */
public final class TwinkleApplication {

    public static void main(String[] args) {
        // Micronaut 启动：从 classpath 加载 application.properties（含 twinkle.profile）。
        // 是否分进程由 profile 与外部启动脚本决定，Micronaut 启动 API 不感知进程边界。
        Micronaut.run(TwinkleApplication.class, args);
    }

    private TwinkleApplication() {
    }
}
