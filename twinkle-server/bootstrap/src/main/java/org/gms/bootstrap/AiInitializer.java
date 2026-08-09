package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.ai.service.AiDailySummaryScheduler;
import org.gms.role.ManagementProcessCondition;

/**
 * AI 启动装配（架构 M3-2：每日总结调度启动）。
 *
 * <p>用 {@code @Context} + 构造注入强制启动装配：context 创建即启动每日总结调度
 * （与 NetworkServerInitializer 模式一致）。单进程档内嵌，2C2G 单线程后台线程。
 *
 * <p>AI 是管理进程专属（single 全内嵌；split 下仅 coordinator 角色装配，频道进程不启 AI）。
 */
@Singleton
@Context
@Requires(condition = ManagementProcessCondition.class)
@Log4j2
public final class AiInitializer {



    public AiInitializer(AiDailySummaryScheduler scheduler) {
        scheduler.start();
        log.info("AI 模块启动装配完成");
    }
}
