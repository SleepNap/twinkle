package org.gms.net.netty.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;

/**
 * 内部通信 JSON 编解码工具（架构 4.5：内部帧负载序列化）。
 *
 * <p>消息总线 payload 都是 Java record（core {@code org.gms.message}），Jackson 原生支持。
 * 反序列化需携带类型名（类全名），经 {@link #decode(String, String)} 恢复真实对象——网络帧
 * 重投的序列化基础（M4 {@code PayloadCodec.MARKER} 只存字符串标记，M6 换真实序列化）。
 */
@Log4j2
public final class JsonCodec {



    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    /** 序列化对象 → JSON 字符串。 */
    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + value.getClass().getName(), e);
        }
    }

    /** 反序列化 JSON → 对象（按类名恢复真实类型）。 */
    @SuppressWarnings("unchecked")
    public static <T> T decode(String json, String typeName) {
        try {
            Class<?> type = Class.forName(typeName);
            return (T) MAPPER.readValue(json, type);
        } catch (ClassNotFoundException e) {
            log.error("JSON 反序列化类型不存在: {}", typeName);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("JSON 反序列化失败: type={}", typeName, e);
            return null;
        }
    }

    /** 反序列化 JSON → 对象（带泛型签名，如 {@code Map<Integer, ChannelInfo>}）。 */
    public static <T> T decode(String json, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("JSON 反序列化失败（泛型）: {}", typeRef.getType().getTypeName(), e);
            return null;
        }
    }

    /** 类型名（类全名，配合 {@link #decode} 用）。 */
    public static String typeName(Object value) {
        return value.getClass().getName();
    }
}
