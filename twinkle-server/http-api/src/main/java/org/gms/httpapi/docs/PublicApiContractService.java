package org.gms.httpapi.docs;

import org.gms.i18n.I18nService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 读取已经冻结并随服务发布的公共 API 主版本契约。 */
public final class PublicApiContractService {

    private final I18nService i18n;

    public PublicApiContractService(I18nService i18n) {
        this.i18n = i18n;
    }

    public String openApi(int major) {
        String resource = "openapi/public/v" + major + "/openapi.yaml";
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Public API contract missing for v" + major);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(i18n.message("api.error.openapi_read_failed"), e);
        }
    }
}
