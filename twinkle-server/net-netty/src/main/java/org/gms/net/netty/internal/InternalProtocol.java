package org.gms.net.netty.internal;

/**
 * 内部通信协议负载 DTO（架构 4.5：帧 payload 的 JSON 结构约定）。
 *
 * <p>各帧类型的 payload 均为 JSON，经 {@link JsonCodec} 编解码：
 * <ul>
 *   <li>{@code REGISTER}：{@link RegisterPayload}（频道/管理进程身份上报）。</li>
 *   <li>{@code HEARTBEAT}：{@link HeartbeatPayload}（channelId + 在线数续期）。</li>
 *   <li>{@code EVENT}：{@link EventPayload}（target 逻辑名 + payload 类型名 + payload JSON）。</li>
 *   <li>{@code RPC}：{@link RpcRequest}（方法名 + 参数数组）。</li>
 *   <li>{@code RPC_RESPONSE}：{@link RpcResponse}（ok + 值/错误）。</li>
 * </ul>
 */
public final class InternalProtocol {

    private InternalProtocol() {
    }

    /** REGISTER 帧负载：身份上报（频道进程 channelId>0；管理进程 admin=true、channelId=0）。 */
    public record RegisterPayload(int channelId, String host, int port, boolean admin, int onlineCount) {
    }

    /** HEARTBEAT 帧负载：频道心跳续期。 */
    public record HeartbeatPayload(int channelId, int onlineCount) {
    }

    /** EVENT 帧负载：消息总线投递（target + 负载类型名 + JSON + 可选可靠序号）。 */
    public record EventPayload(String target, String type, String payload,
                               String streamId, Long seq, String messageId) {

        /** 普通投递（无可靠序号）。 */
        public EventPayload(String target, String type, String payload) {
            this(target, type, payload, null, null, null);
        }
    }

    /** RPC 请求负载：方法名 + 参数数组（每参数已 JSON 序列化为字符串，避免类型信息丢失）。 */
    public record RpcRequest(String method, String[] args) {
    }

    /** RPC 响应负载：ok + 值（JSON 字符串，null = 无值/empty Optional）/错误信息。 */
    public record RpcResponse(boolean ok, String value, String error) {

        /** 成功响应。 */
        public static RpcResponse ok(String value) {
            return new RpcResponse(true, value, null);
        }

        /** 失败响应。 */
        public static RpcResponse fail(String error) {
            return new RpcResponse(false, null, error);
        }
    }
}
