package org.gms.service.agent;

/**
 * AI 治理（准入 / 策略 / 额度）拒绝异常。
 *
 * <p>{@code code} 是稳定的机器可读错误码；{@link Kind} 表达拒绝的性质，供 HTTP 层决定
 * 状态码与是否可重试——本类在 core，不引入 HTTP 概念。
 */
public final class AiGovernanceException extends RuntimeException {

    private final String code;
    private final Kind kind;

    /** 默认按额度类拒绝（可重试），保持既有调用点语义不变。 */
    public AiGovernanceException(String code, String message) {
        this(code, message, Kind.QUOTA);
    }

    public AiGovernanceException(String code, String message, Kind kind) {
        super(message);
        this.code = code;
        this.kind = kind;
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    /** 拒绝性质。 */
    public enum Kind {

        /** 额度/余额耗尽：等额度恢复或充值后可再试。 */
        QUOTA,
        /** 策略禁止（模型不在白名单、账号被禁用）：重试多少次都不会通过。 */
        POLICY,
        /** 服务被管理员整体关闭：属于服务不可用而非调用方的错。 */
        UNAVAILABLE
    }
}
