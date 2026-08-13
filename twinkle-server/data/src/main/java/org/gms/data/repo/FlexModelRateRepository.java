package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.ModelRate;
import org.gms.data.mapper.ModelRateMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex 模型倍率仓储实现。 */
public final class FlexModelRateRepository implements ModelRateRepository {

    private final ModelRateMapper mapper;

    public FlexModelRateRepository(ModelRateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ModelRate> findByModelKey(String modelKey) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(ModelRate::getModelKey).eq(modelKey)));
    }

    @Override
    public List<ModelRate> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create().orderBy(ModelRate::getId, true));
    }

    @Override
    public void insert(ModelRate rate) {
        mapper.insertSelective(rate);
    }

    @Override
    public void update(ModelRate rate) {
        mapper.update(rate);
    }
}
