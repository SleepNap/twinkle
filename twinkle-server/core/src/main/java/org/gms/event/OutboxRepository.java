package org.gms.event;

import java.util.List;

/**
 * 消息总线 outbox 仓库接口（架构 4.5 可靠性三件套：持久化队列）。
 *
 * <p>接口放 core（可靠总线在 core），data 模块实现（MyBatis-Flex）。core 不依赖 data，
 * data 依赖 core——接口先行、实现后置（铁律 1）。
 */
public interface OutboxRepository {

    /** outbox 行（发送侧先落 PENDING → 投递 → DELIVERED → ack → ACKED）。 */
    record OutboxRow(long id, String streamId, long seq, String messageId, String target,
                     String payloadType, String payload, String status) {

        public static final String PENDING = "PENDING";
        public static final String DELIVERED = "DELIVERED";
        public static final String ACKED = "ACKED";
    }

    /** 落 outbox（PENDING，待投递）。返回生成的 id（自增主键）。 */
    long insert(OutboxRow row);

    /** 取出全部未 ACKED 的待投递/投递中消息（启动重投 + 定时重试用，按 id 序）。 */
    List<OutboxRow> findPending();

    /** 标记投递完成（DELIVERED，等接收方 ack）。 */
    void markDelivered(long id);

    /** 标记接收方已 ack（ACKED，幂等去重落定）。 */
    void markAcked(String messageId);
}
