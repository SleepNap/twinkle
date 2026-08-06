package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.script.ScriptManager;

/**
 * 启动期脚本装配校验（架构 M2-4：脚本目录读不到在启动期暴露）。
 *
 * <p>构造注入 {@link ScriptManager} 即强制触发 ScriptRepository 初始化——
 * {@code twinkle.script.path} 指向不存在目录时 context 启动即失败（架构 6.4）。
 */
@Singleton
@Context
public final class ScriptInitializer {

    private static final Logger LOG = LogManager.getLogger(ScriptInitializer.class);

    public ScriptInitializer(ScriptManager scriptManager) {
        LOG.info("脚本引擎装配完成：根={}", scriptManager.root());
    }
}
