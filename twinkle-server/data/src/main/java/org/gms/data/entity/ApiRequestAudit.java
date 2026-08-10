package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/** 能力面调用审计；不记录凭据和请求正文，避免二次泄密。 */
@Table("api_request_audit")
@Getter
@Setter
public class ApiRequestAudit {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String requestId;
    private Long apiKeyId;
    private String keyPrefix;
    private String method;
    private String path;
    private String requiredScope;
    private String outcome;
    private int statusCode;
    private String remoteAddress;
    private int elapsedMs;
    private String createdAt;
}
