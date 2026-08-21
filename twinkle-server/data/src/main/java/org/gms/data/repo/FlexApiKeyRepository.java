package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.ApiKeyRecord;
import org.gms.data.mapper.ApiKeyMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex API-key 仓储实现。 */
public final class FlexApiKeyRepository implements ApiKeyRepository {

    private final ApiKeyMapper mapper;

    public FlexApiKeyRepository(ApiKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(ApiKeyRecord::getKeyPrefix).eq(keyPrefix)));
    }

    @Override
    public List<ApiKeyRecord> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create()
                .orderBy(ApiKeyRecord::getId, false));
    }

    @Override
    public void insert(ApiKeyRecord record) {
        mapper.insertSelective(record);
    }

    @Override
    public void update(ApiKeyRecord record) {
        mapper.update(record);
    }
}
