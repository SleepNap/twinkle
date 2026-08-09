package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息总线流序号实体（架构 4.5 单一属主序号落点，V5 迁移建表）。
 *
 * <p>接收侧幂等去重 + 按序投递的持久化状态：{@code stream_id → last_delivered_seq}。
 * M4 内存版（ReliableEventBus 的 deliveredSeq Map）；M6 跨进程落此表，重启不丢去重状态
 * （进程崩了重投不重复应用）。
 */
@Table("bus_stream_state")
@Getter
@Setter
public class BusStreamEntity {

    @Id(keyType = KeyType.None)
    private String streamId;
    /** 该逻辑流已投递的最大序号（0 = 未投递）。 */
    private long lastDeliveredSeq;
}
