package org.gms.i18n;

import java.util.Locale;

/**
 * 全局国际化入口。
 *
 * <p>业务代码只持有稳定 message key；语言选择发生在系统边界（HTTP 请求、账号会话、
 * Web 控制台），协议码、错误码、字段 ID 等机器契约不得翻译。
 */
public interface I18nService {

    /** 当前服务端全局语言，由 {@code twinkle.service.language} 在启动时确定。 */
    Locale locale();

    /** 解析 message key；缺失 key 返回 key 本身，使问题可观察且不阻断请求。 */
    String message(String key, Object... arguments);
}
