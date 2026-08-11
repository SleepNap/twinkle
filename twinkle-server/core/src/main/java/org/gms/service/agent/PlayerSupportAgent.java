package org.gms.service.agent;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 玩家侧只读值班 GM 契约。
 *
 * <p>频道层只依赖此稳定接口，不依赖具体模型或 AI 编排库。实现必须异步完成，禁止在
 * Netty/游戏 tick 线程上等待外部模型。
 */
public interface PlayerSupportAgent {

    /** 当前进程是否提供玩家侧 Agent。 */
    public boolean available();

    /** 异步处理玩家问题；实现不得执行写操作。 */
    public CompletionStage<Reply> ask(PlayerQuestion question);

    /** 玩家问题的最小可信上下文；message 本身始终视为不可信输入。 */
    public record PlayerQuestion(long characterId, String characterName, long sessionId, String message) {
    }

    /** 返回给玩家的文本和对应只读取证审计引用。 */
    public record Reply(String text, List<String> auditRefs) {
        public Reply {
            auditRefs = auditRefs == null ? List.of() : List.copyOf(auditRefs);
        }
    }
}
