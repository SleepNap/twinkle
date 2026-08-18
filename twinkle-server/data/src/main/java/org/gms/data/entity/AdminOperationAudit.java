package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/** Web 控制台不可抵赖审计。只保存安全摘要，不落请求正文与凭据（红线 23）。 */
@Table("admin_operation_audit")
@Getter
@Setter
public class AdminOperationAudit {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String requestId;
    private Long accountId;
    private String accountName;
    private String method;
    private String path;
    private String operation;
    private String reason;
    private String beforeSummary;
    private String afterSummary;
    private String resultStatus;
    private Integer statusCode;
    private String remoteAddress;
    private Integer elapsedMs;
    private String createdAt;
}
