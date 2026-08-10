package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 对外能力面 API-key 记录。只保存不可逆 SHA-256 摘要，明文仅在签发时返回一次。
 */
@Table("api_key_records")
@Getter
@Setter
public class ApiKeyRecord {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String credentialId;
    private String keyPrefix;
    private String secretHash;
    private String subjectId;
    private String subjectDisplayName;
    private String createdBySubjectId;
    private String serverId;
    private Long ownerAccountId;
    private String displayName;
    private String scopes;
    private String createdAt;
    private String expiresAt;
    private String disabledAt;
    private String revokedAt;
    private String rotatedFromPrefix;
    private String lastUsedAt;
    private String permissionVersion;
}
