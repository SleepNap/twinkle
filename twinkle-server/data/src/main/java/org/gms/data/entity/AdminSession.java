package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Web 控制台管理员会话。只保存不可逆 SHA-256 摘要，明文 token 仅在登录时返回一次。
 */
@Table("admin_session")
@Getter
@Setter
public class AdminSession {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String tokenPrefix;
    private String tokenHash;
    private Long accountId;
    private String createdAt;
    private String expiresAt;
    private String lastUsedAt;
    private String revokedAt;
    private String remoteAddress;
}
