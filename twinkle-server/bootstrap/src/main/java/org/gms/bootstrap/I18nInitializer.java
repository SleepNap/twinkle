package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import org.gms.i18n.I18n;
import org.gms.i18n.I18nService;

/**
 * 启动期装配 i18n 静态门面。
 *
 * <p>构造注入 {@link I18nService} 并 {@link I18n#install}，使无法经容器注入的代码
 * （游戏对象、静态工具、手动构造的 PacketHandler）能经静态门面取服务端语言文案。
 * 用 {@code @Context} 在 context 启动即装配（不依赖 HTTP 事件，同 {@link DataLayerInitializer}），
 * 确保业务日志/异常产生前门面已就绪；即使装配顺序有先后，{@link I18n} 对未 install
 * 也降级返回 key 本身，不 NPE。
 */
@Context
public final class I18nInitializer {

    public I18nInitializer(I18nService i18nService) {
        I18n.install(i18nService);
    }
}
