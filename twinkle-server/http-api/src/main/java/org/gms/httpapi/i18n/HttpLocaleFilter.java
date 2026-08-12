package org.gms.httpapi.i18n;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.gms.i18n.I18nService;
import org.reactivestreams.Publisher;

import java.util.Locale;

/**
 * 全 HTTP 面的服务端语言边界：所有响应使用 {@code twinkle.service.language}，
 * 并写出 Content-Language。请求不能覆盖服务端语言；业务错误码和 JSON 字段名保持稳定、不翻译。
 */
@Filter("/**")
public final class HttpLocaleFilter implements HttpServerFilter, Ordered {

    private final I18nService i18n;

    public HttpLocaleFilter(I18nService i18n) {
        this.i18n = i18n;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.before();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        Locale locale = i18n.locale();
        return io.micronaut.core.async.publisher.Publishers.map(chain.proceed(request), response -> {
            response.header(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag());
            return response;
        });
    }
}
