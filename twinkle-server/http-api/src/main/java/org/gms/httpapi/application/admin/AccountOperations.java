package org.gms.httpapi.application.admin;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import java.util.Map;

/** 可替换用例的稳定入口。 */
public interface AccountOperations {
    public HttpResponse<?> list(String query,
                                String status,
                                int offset,
                                int limit);
    public HttpResponse<?> create(HttpRequest<?> request, Map<String, Object> body);
    public HttpResponse<?> update(HttpRequest<?> request,
                                  long accountId,
                                  Map<String, Object> body);
    public HttpResponse<?> delete(HttpRequest<?> request, long accountId);
    public HttpResponse<?> detail(long accountId);
    public HttpResponse<?> updateRestrictions(HttpRequest<?> request,
                                              long accountId,
                                              Map<String, Object> body);
    public HttpResponse<?> forceOffline(HttpRequest<?> request, long accountId);
    public HttpResponse<?> generateTemporaryPassword(HttpRequest<?> request,
                                                     long accountId,
                                                     Map<String, Object> body);
}
