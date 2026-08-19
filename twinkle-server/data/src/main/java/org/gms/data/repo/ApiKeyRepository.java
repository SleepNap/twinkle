package org.gms.data.repo;

import org.gms.data.entity.ApiKeyRecord;

import java.util.List;
import java.util.Optional;

/** API-key 持久化契约。 */
public interface ApiKeyRepository {

    public Optional<ApiKeyRecord> findByPrefix(String keyPrefix);

    /** 按凭据 ID 反查；AI 计费只拿得到 credentialId，用它还原计费主体与 scope。 */
    public Optional<ApiKeyRecord> findByCredentialId(String credentialId);

    public List<ApiKeyRecord> findAll();

    public void insert(ApiKeyRecord record);

    public void update(ApiKeyRecord record);
}
