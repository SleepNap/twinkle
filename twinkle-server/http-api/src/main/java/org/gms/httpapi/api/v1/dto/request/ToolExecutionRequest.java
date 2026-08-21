package org.gms.httpapi.api.v1.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1 Tool 执行请求。额外字段会被保留并交给协议校验器拒绝，避免 DTO 化后放宽既有契约。
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class ToolExecutionRequest {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "固定为 0.1")
    private String contractVersion;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128)
    private String requestId;
    @Schema(maxLength = 128)
    private String taskId;
    @Schema(maxLength = 128)
    private String stepId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String toolId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "当前固定为 1.0.0")
    private String toolVersion;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, Object> input;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "v1 尚不支持 dry-run，必须为 false")
    private Boolean dryRun;
    private String idempotencyKey;
    private String approvalToken;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private ToolClientContextRequest clientContext;
    private final Map<String, Object> additionalFields = new LinkedHashMap<>();

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getToolId() {
        return toolId;
    }

    public void setToolId(String toolId) {
        this.toolId = toolId;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getApprovalToken() {
        return approvalToken;
    }

    public void setApprovalToken(String approvalToken) {
        this.approvalToken = approvalToken;
    }

    public ToolClientContextRequest getClientContext() {
        return clientContext;
    }

    public void setClientContext(ToolClientContextRequest clientContext) {
        this.clientContext = clientContext;
    }

    public Map<String, Object> additionalFields() {
        return Map.copyOf(additionalFields);
    }

    @JsonAnySetter
    public void putAdditionalField(String name, Object value) {
        additionalFields.put(name, value);
    }
}
