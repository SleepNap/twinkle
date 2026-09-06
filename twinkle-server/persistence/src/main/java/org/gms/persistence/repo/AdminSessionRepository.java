package org.gms.persistence.repo;

import org.gms.persistence.entity.AdminSession;

import java.util.Optional;

/** 管理员会话持久化契约（强鉴权）。 */
public interface AdminSessionRepository {

    public Optional<AdminSession> findByPrefix(String tokenPrefix);

    public void insert(AdminSession session);

    public void update(AdminSession session);
}
