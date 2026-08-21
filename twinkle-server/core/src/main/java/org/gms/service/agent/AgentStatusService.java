package org.gms.service.agent;

/**
 * AI 运行态查询的稳定契约（模型标识、连通性、降级与最近错误）。
 *
 * <p>与 {@link ServerAgentService} 分开：后者的语义是"执行一次调查"，健康状态不属于执行契约。
 * 拆开也让 {@code UnavailableServerAgentService} 与其测试桩不必为状态字段陪改。
 *
 * <p>管理面（http-api）不依赖 ai 模块，只能经本契约读 AI 运行态；实现由 AI 门面提供，
 * bootstrap 在未装配 AI 时给出不可用兜底。
 */
public interface AgentStatusService {

    /** 当前进程是否已装配可用模型。 */
    public boolean available();

    /** 模型标识（{@code provider/modelName}）；未装配时为空串。 */
    public String modelDescriptor();

    /** 是否为外部模型（非本地规则模型）。 */
    public boolean externalModel();

    /** 累计调用次数。 */
    public long callCount();

    /**
     * 连续失败次数；{@code 0} 表示最近一次调用成功。
     *
     * <p>连续失败达阈值即视为降级——外部模型不可达时调用会持续抛错，这是运维最需要看到的信号。
     */
    public int consecutiveFailures();

    /** 最近一次失败的摘要（异常类型 + 截断消息）；无失败时为空串。不含密钥与提示词原文。 */
    public String lastError();

    /** 最近一次失败的时刻（ISO-8601）；无失败时为空串。 */
    public String lastErrorAt();
}
