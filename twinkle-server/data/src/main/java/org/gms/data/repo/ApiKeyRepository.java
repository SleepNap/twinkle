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

    /** 按计费账号反查名下所有凭据；AI 策略变更后需刷新它们的 permissionVersion。 */
    public List<ApiKeyRecord> findByOwnerAccountId(Long ownerAccountId);

    public void insert(ApiKeyRecord record);

    public void update(ApiKeyRecord record);
}
