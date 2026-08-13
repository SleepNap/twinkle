package org.gms.data.repo;

import org.gms.data.entity.ModelRate;

import java.util.List;
import java.util.Optional;

/** 模型倍率持久化契约。 */
public interface ModelRateRepository {

    public Optional<ModelRate> findByModelKey(String modelKey);

    public List<ModelRate> findAll();

    public void insert(ModelRate rate);

    public void update(ModelRate rate);
}
