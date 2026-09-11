package org.gms.httpapi.application.query;

import io.micronaut.http.HttpResponse;

/** 可替换用例的稳定入口。 */
public interface CharacterQueries {
    public HttpResponse<?> detail(long accountId, long characterId);
}
