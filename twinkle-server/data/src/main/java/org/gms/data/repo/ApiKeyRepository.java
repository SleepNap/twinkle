package org.gms.data.repo;

import org.gms.data.entity.ApiKeyRecord;

import java.util.List;
import java.util.Optional;

/** API-key 持久化契约。 */
public interface ApiKeyRepository {

    public Optional<ApiKeyRecord> findByPrefix(String keyPrefix);

    public List<ApiKeyRecord> findAll();

    public void insert(ApiKeyRecord record);

    public void update(ApiKeyRecord record);
}
