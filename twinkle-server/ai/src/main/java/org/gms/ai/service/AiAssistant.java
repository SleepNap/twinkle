package org.gms.ai.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AI 助手声明式接口（架构 M3-2：AiServices 声明式 Agent + 工具）。
 *
 * <p>LangChain4j 声明式 Agent：接口方法标注 {@code @SystemMessage}/{@code @UserMessage}，
 * 由 AiServices 生成代理。三种形态：
 * <ul>
 *   <li>{@link #chat(String)} 阻塞对话（工具自动循环）</li>
 *   <li>{@link #stream(String)} 流式对话（TokenStream）</li>
 *   <li>{@link #onlineReport()} 结构化输出（返回 POJO，模型自动解析）</li>
 * </ul>
 *
 * <p>实现由 AiServices 在装配期生成（无手写实现类）。
 */
public interface AiAssistant {

    /** 阻塞对话：返回最终文本（工具调用自动循环）。 */
    @SystemMessage("你是冒险岛后台 AI 助手，可查询在线统计、账号信息等。查询数据时调用可用工具。")
    String chat(@UserMessage String message);

    /** 流式对话：返回 TokenStream，调用方 onPartialResponse 订阅增量。 */
    @SystemMessage("你是冒险岛后台 AI 助手，可查询在线统计、账号信息等。查询数据时调用可用工具。")
    TokenStream stream(@UserMessage String message);

    /** 结构化输出：在线统计报表（POJO 自动解析）。 */
    @SystemMessage("你是冒险岛后台 AI 助手。")
    OnlineReport onlineReport(@UserMessage String message);
}
