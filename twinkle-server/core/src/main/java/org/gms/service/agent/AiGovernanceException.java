package org.gms.service.agent;

/** AI 治理（准入 / 额度）拒绝异常。code 用于映射 HTTP 429 响应的 error 码。 */
public final class AiGovernanceException extends RuntimeException {

    private final String code;

    public AiGovernanceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
