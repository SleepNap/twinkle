package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息总线 outbox 实体（架构 4.5 可靠性三件套：持久化队列，V5 迁移建表）。
 *
 * <p>发消息先落此表（PENDING）→ 投递 → DELIVERED → 接收方 ack 后 ACKED。
 * 进程崩了重投未 ACKED（投递成功才 ack，崩了重发）；接收方按 {@code messageId} 幂等去重、
 * 按 {@code streamId + seq} 单一属主序号按序投递。
 */
@Table("bus_outbox_queue")
@Getter
@Setter
public class BusOutboxEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    /** 逻辑流 id（同一逻辑流按序投递，单一属主序号）。 */
    private String streamId;
    /** 流内序号（单调递增，接收方按 last_delivered_seq 去重）。 */
    private long seq;
    /** 全局唯一消息 id（幂等去重依据）。 */
    private String messageId;
    /** 投递目标（逻辑名：channel:{id} / * 广播）。 */
    private String target;
    /** 负载类型（全限定类名，接收方反射反序列化）。 */
    private String payloadType;
    /** 负载序列化（JSON）。 */
    private String payload;
    /** 状态：PENDING / DELIVERED / ACKED。 */
    private String status;
    private String createdAt;
    private String deliveredAt;

    /** 状态常量。 */
    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String ACKED = "ACKED";
}
