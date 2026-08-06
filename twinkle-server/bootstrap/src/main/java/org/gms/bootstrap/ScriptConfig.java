package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.domain.script.ScriptEngine;
import org.gms.domain.script.ScriptManager;
import org.gms.domain.script.ScriptRepository;

import java.nio.file.Path;

/**
 * 脚本引擎装配（架构 M2-4 脚本引擎：GraalVM JS + 数据源定位 + L2 重载入口）。
 *
 * <p>{@code twinkle.script.path} 直接指定脚本目录（架构 6.4：单份、读不到报错——
 * {@link ScriptRepository} 构造时目录不存在即抛异常，context 启动即失败）。
 * ScriptEngine 单例（2C2G 预算：GraalVM context 常驻约 60-100M，不复用多实例）。
 */
@Factory
public class ScriptConfig {

    @Bean(preDestroy = "close")
    @Singleton
    public ScriptEngine scriptEngine() {
        return new ScriptEngine();
    }

    @Bean
    @Singleton
    public ScriptRepository scriptRepository(
            @Property(name = "twinkle.script.path", defaultValue = "./scripts") String path) {
        return new ScriptRepository(Path.of(path));
    }

    @Bean
    @Singleton
    public ScriptManager scriptManager(ScriptEngine engine, ScriptRepository repository) {
        return new ScriptManager(engine, repository);
    }
}
