package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.ai.service.AiDailySummaryScheduler;
import org.gms.role.AiEnabledCondition;

/**
 * AI 启动装配（架构 M3-2：每日总结调度启动）。
 *
 * <p>用 {@code @Context} + 构造注入强制启动装配：context 创建即启动每日总结调度
 * （与 NetworkServerInitializer 模式一致）。单进程档内嵌，2C2G 单线程后台线程。
 *
 * <p><b>可选功能（2C2G 红线）</b>：AI 默认不装配，经 {@code twinkle.ai.enabled=true}
 * 显式开启（条件 {@link AiEnabledCondition} 已内置管理进程前提）。
 */
@Singleton
@Context
@Requires(condition = AiEnabledCondition.class)
@Log4j2
public final class AiInitializer {



    public AiInitializer(AiDailySummaryScheduler scheduler) {
        scheduler.start();
        log.info(I18n.message("log.bootstrap.ai_ready"));
    }
}
