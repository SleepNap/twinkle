package org.gms;

import lombok.extern.slf4j.Slf4j;
import org.gms.config.TwinkleProperties;
import org.gms.i18n.LogBundle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@Slf4j
public class TwinkleApplication {

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        ConfigurableApplicationContext context = SpringApplication.run(TwinkleApplication.class, args);

        TwinkleProperties settings = context.getBean(TwinkleProperties.class);
        LogBundle logs = context.getBean(LogBundle.class);
        BuildProperties build = context.getBean(BuildProperties.class);

        log.info(logs.get("server.started", settings.locale(), settings.name(), settings.role(), settings.language()));
        log.info(logs.get("server.version", settings.locale(), build.getVersion() == null ? "unknown" : build.getVersion()));
        log.info(logs.get("startup.elapsed", settings.locale(), (System.currentTimeMillis() - start) / 1000D));
    }
}
