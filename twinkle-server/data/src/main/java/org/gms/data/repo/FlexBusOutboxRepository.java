package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.BusOutboxEntity;
import org.gms.data.mapper.BusOutboxMapper;
import org.gms.event.OutboxRepository;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Flex 实现的消息总线 outbox 仓库（M4 可靠投递，架构 4.5）。
 *
 * <p>SQL 用两库公共子集（SELECT/UPDATE/ORDER BY，红线 14：方言点进 db-dialect，此处无方言裸写）。
 */
public class FlexBusOutboxRepository implements OutboxRepository {

    private final BusOutboxMapper mapper;

    public FlexBusOutboxRepository(BusOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(OutboxRow row) {
        BusOutboxEntity e = new BusOutboxEntity();
        e.setStreamId(row.streamId());
        e.setSeq(row.seq());
        e.setMessageId(row.messageId());
        e.setTarget(row.target());
        e.setPayloadType(row.payloadType());
        e.setPayload(row.payload());
        e.setStatus(OutboxRow.PENDING);
        e.setCreatedAt(Instant.now().toString());
        mapper.insert(e);
        return e.getId();
    }

    @Override
    public List<OutboxRow> findPending() {
        // 未 ACKED 的按 id 序（重投保序，单一属主序号投递）
        return mapper.selectListByQuery(QueryWrapper.create()
                        .where(BusOutboxEntity::getStatus).ne(OutboxRow.ACKED)
                        .orderBy(BusOutboxEntity::getId).asc())
                .stream()
                .map(this::toRow)
                .toList();
    }

    @Override
    public void markDelivered(long id) {
        BusOutboxEntity e = new BusOutboxEntity();
        e.setId(id);
        e.setStatus(OutboxRow.DELIVERED);
        e.setDeliveredAt(Instant.now().toString());
        mapper.update(e);
    }

    @Override
    public void markAcked(String messageId) {
        BusOutboxEntity e = new BusOutboxEntity();
        e.setStatus(OutboxRow.ACKED);
        mapper.updateByQuery(e, QueryWrapper.create()
                .where(BusOutboxEntity::getMessageId).eq(messageId));
    }

    private OutboxRow toRow(BusOutboxEntity e) {
        return new OutboxRow(e.getId(), e.getStreamId(), e.getSeq(), e.getMessageId(),
                e.getTarget(), e.getPayloadType(), e.getPayload(), e.getStatus());
    }
}
