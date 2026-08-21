package org.gms.i18n;

import jakarta.inject.Singleton;

/**
 * 将容器管理的 {@link I18nService} 安装到静态 {@link I18n} 门面。
 *
 * <p>需要在启动期产生日志的 Bean 应显式依赖本 Bean，以建立确定的初始化顺序；
 * 不能只依赖多个 {@code @Context} Bean 的扫描先后。
 */
@Singleton
public final class I18nBootstrap {

    public I18nBootstrap(I18nService i18nService) {
        I18n.install(i18nService);
    }
}
