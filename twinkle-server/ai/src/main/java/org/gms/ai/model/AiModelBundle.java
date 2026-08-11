package org.gms.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * Agent 模型装配结果：阻塞/流式模型与安全展示信息放在一个不可变值对象中。
 */
public record AiModelBundle(ChatModel chatModel, StreamingChatModel streamingChatModel,
                            String provider, String modelName, boolean external) {

    /** 不包含 base URL 与密钥的安全模型标识。 */
    public String descriptor() {
        return provider + "/" + modelName;
    }
}
