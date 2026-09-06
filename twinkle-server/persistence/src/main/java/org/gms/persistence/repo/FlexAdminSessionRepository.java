package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.persistence.entity.AdminSession;
import org.gms.persistence.mapper.AdminSessionMapper;

import java.util.Optional;

/** MyBatis-Flex 管理员会话仓储实现。 */
public final class FlexAdminSessionRepository implements AdminSessionRepository {

    private final AdminSessionMapper mapper;

    public FlexAdminSessionRepository(AdminSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AdminSession> findByPrefix(String tokenPrefix) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(AdminSession::getTokenPrefix).eq(tokenPrefix)));
    }

    @Override
    public void insert(AdminSession session) {
        mapper.insertSelective(session);
    }

    @Override
    public void update(AdminSession session) {
        mapper.update(session);
    }
}
