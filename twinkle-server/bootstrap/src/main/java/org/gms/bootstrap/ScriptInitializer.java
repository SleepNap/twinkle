package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.domain.script.ScriptManager;
import org.gms.role.ChannelProcessCondition;

/**
 * 启动期脚本装配校验（架构 M2-4：脚本目录读不到在启动期暴露）。
 *
 * <p>构造注入 {@link ScriptManager} 即强制触发 ScriptRepository 初始化——
 * {@code twinkle.script.path} 指向不存在目录时 context 启动即失败（架构 6.4）。
 *
 * <p>脚本引擎是频道进程专属（同 {@link ScriptConfig}）。
 */
@Singleton
@Context
@Requires(condition = ChannelProcessCondition.class)
@Log4j2
public final class ScriptInitializer {


    public ScriptInitializer(ScriptManager scriptManager) {
        log.info("脚本引擎装配完成：根={}", scriptManager.root());
    }
}
