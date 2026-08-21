package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import org.gms.i18n.I18nBootstrap;

/**
 * 启动期装配 i18n 静态门面。
 *
 * <p>构造注入 {@link I18nBootstrap}，使无法经容器注入的代码
 * （游戏对象、静态工具、手动构造的 PacketHandler）能经静态门面取服务端语言文案。
 * 用 {@code @Context} 在 context 启动即装配，不依赖 HTTP 事件，
 * 具体安装职责位于 core；数据源等早期启动 Bean 也显式依赖它，不再依赖多个
 * {@code @Context} Bean 之间未定义的初始化顺序。
 */
@Context
public final class I18nInitializer {

    public I18nInitializer(I18nBootstrap i18nBootstrap) {
        // 构造注入即建立启动依赖，安装由 I18nBootstrap 完成。
    }
}
