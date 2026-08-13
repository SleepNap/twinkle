package org.gms.net.netty.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.event.PayloadCodec;

/**
 * 可靠总线负载 JSON 编解码（架构 4.5：outbox 持久化队列 + 网络帧重投的真实序列化）。
 *
 * <p>M4 单进程内用 {@link PayloadCodec#MARKER}（只存字符串标记，反射重建空对象）——投递真实对象
 * 不经网络，outbox 仅持久化记录。M6 跨进程：outbox 负载经网络重投，必须真实序列化。
 * 消息负载都是 Java record（core {@code org.gms.message} / {@code OnlinePlayerEvents}），
 * Jackson 原生支持。
 *
 * <p>装配由 bootstrap 注入 {@link org.gms.event.ReliableEventBus}（替换 MARKER）。
 */
@Log4j2
public final class JsonPayloadCodec implements PayloadCodec {



    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String encode(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(I18n.message("error.bus.payload_serialize_failed", payload.getClass().getName()), e);
        }
    }

    @Override
    public Object decode(String payload, String payloadType) {
        try {
            Class<?> type = Class.forName(payloadType);
            return MAPPER.readValue(payload, type);
        } catch (ClassNotFoundException e) {
            log.error(I18n.message("log.bus.payload_type_not_found"), payloadType);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(I18n.message("log.bus.payload_deserialize_failed"), payloadType, e);
            return null;
        }
    }
}
