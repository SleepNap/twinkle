package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.role.ChannelProcessCondition;
import org.gms.wz.WzResourceRegistry;

import java.nio.file.Path;

/** 启动期完成 WZ 数据装配，并输出一条稳定的路径日志。 */
@Singleton
@Context
@Requires(condition = ChannelProcessCondition.class)
@Log4j2
public final class WzInitializer {

    public WzInitializer(WzResourceRegistry resources,
                         @Property(name = "twinkle.wz.path", defaultValue = "./wz") String wzPath) {
        // 注册中心构造完成代表全部已注册资源均已成功建立首代快照。
        log.info(I18n.message("log.bootstrap.wz_ready"), Path.of(wzPath));
    }
}
