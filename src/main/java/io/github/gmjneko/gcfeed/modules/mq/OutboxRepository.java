package io.github.gmjneko.gcfeed.modules.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.gmjneko.gcfeed.modules.mq.entity.EventOutbox;
import io.github.gmjneko.gcfeed.modules.mq.mapper.EventOutboxMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** event_outbox 表的读写：插入待发送行、按批取待发送、把行标记为已发送。 */
@Repository
public class OutboxRepository {

    private final EventOutboxMapper mapper;

    public OutboxRepository(EventOutboxMapper mapper) {
        this.mapper = mapper;
    }

    public void insertPending(String eventId, String eventType, String payload) {
        EventOutbox row = new EventOutbox();
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setPayload(payload);
        row.setStatus(EventOutbox.PENDING);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }

    /** 按自增 ID 升序取待发送行：补发顺序与写入顺序一致，LIMIT 控制单批规模。 */
    public List<EventOutbox> pending(int limit) {
        return mapper.selectList(Wrappers.<EventOutbox>lambdaQuery()
                .eq(EventOutbox::getStatus, EventOutbox.PENDING)
                .orderByAsc(EventOutbox::getId)
                .last("LIMIT " + limit));
    }

    /** 条件更新要求当前仍是待发送：afterCommit 线程与补发任务同时标记同一行时，后一个 UPDATE 只影响 0 行。 */
    public void markSent(Long id) {
        mapper.update(Wrappers.<EventOutbox>lambdaUpdate()
                .eq(EventOutbox::getId, id)
                .eq(EventOutbox::getStatus, EventOutbox.PENDING)
                .set(EventOutbox::getStatus, EventOutbox.SENT));
    }
}
