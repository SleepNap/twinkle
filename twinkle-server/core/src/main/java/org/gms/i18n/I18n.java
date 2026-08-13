package org.gms.i18n;

import java.util.Locale;

/**
 * i18n 静态门面。
 *
 * <p>为无法通过 Micronaut 容器注入 {@link I18nService} 的代码（游戏对象、静态工具、
 * 手动构造的 PacketHandler）提供统一入口。委托实例由 bootstrap 的 {@code I18nInitializer}
 * 在进程启动时 {@link #install(I18nService)} 注入。
 *
 * <p>占位符约定：日志模板用 {@code {}}（交由 log4j 填充，调用时无参），异常 / 消息 /
 * 游戏内提示用 {@code {0}}（交由 MessageFormat 填充，调用时有参）。
 */
public final class I18n {

    private static volatile I18nService delegate;

    private I18n() {
    }

    public static void install(I18nService service) {
        delegate = service;
    }

    public static String message(String key, Object... arguments) {
        I18nService service = delegate;
        // 未 install（启动顺序 / 测试未初始化）时降级返回 key 本身，避免 NPE；正常启动由 I18nInitializer 保证 install。
        return service == null ? key : service.message(key, arguments);
    }

    public static Locale locale() {
        I18nService service = delegate;
        return service == null ? Locale.ROOT : service.locale();
    }
}
