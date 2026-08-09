package org.gms.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 可靠总线负载编解码（架构 4.5：outbox 持久化队列的负载序列化）。
 *
 * <p>M4 单进程内：发送侧把真实对象直接投给 delegate（进程内送达），outbox 的 payload 只需
 * 可重建的标记——默认 {@code MarkerPayloadCodec}（存 messageId + 类型，反序列化重建空标记）。
 * M6 跨进程时换 {@code JsonPayloadCodec}（真实序列化，网络帧重投），接口不变（铁律 1）。
 */
public interface PayloadCodec {

    /** 序列化负载 → outbox payload 列（字符串）。 */
    String encode(Object payload);

    /** 反序列化 outbox payload → 投递对象（M4 重建标记；M6 重建真实对象）。 */
    Object decode(String payload, String payloadType);

    /** 默认 M4 实现：字符串标记（单进程投递真实对象，outbox 仅持久化记录）。 */
    PayloadCodec MARKER = new PayloadCodec() {
        @Override
        public String encode(Object payload) {
            return String.valueOf(payload);
        }

        @Override
        public Object decode(String payload, String payloadType) {
            try {
                return Class.forName(payloadType).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                LogManager.getLogger(PayloadCodec.class).error("可靠总线重投重建失败: type={}", payloadType, e);
                return null;
            }
        }
    };
}
