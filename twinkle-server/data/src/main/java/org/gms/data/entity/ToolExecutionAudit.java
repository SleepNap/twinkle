package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/** twish Tool 权威审计；只保存安全摘要，不复制 Tool 输出或 Credential。 */
@Table("tool_execution_audit")
@Getter
@Setter
public class ToolExecutionAudit {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String auditRef;
    private String executionId;
    private String requestId;
    private String taskId;
    private String stepId;
    private String subjectId;
    private String credentialId;
    private String source;
    private String serverId;
    private String toolId;
    private String toolVersion;
    private String requiredScopes;
    private String authorizationResult;
    private String policyVersion;
    private String parameterSummary;
    private String resultStatus;
    private String errorCode;
    private String intentSummary;
    private String startedAt;
    private String completedAt;
}
