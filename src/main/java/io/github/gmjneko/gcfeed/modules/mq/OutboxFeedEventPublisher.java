package io.github.gmjneko.gcfeed.modules.mq;

import io.github.gmjneko.gcfeed.modules.embedding.service.VideoPublishedEvent;
import io.github.gmjneko.gcfeed.modules.mq.entity.EventOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

/**
 * 业务事务内只写 Outbox。提交后发送；确认失败留给定时任务补发，不让发布接口失败。
 *
 * <p>可靠性由三层拼成：afterCommit 在提交成功后立刻试发一次；每次发送同步等待 RabbitMQ 的
 * publisher confirm 最多 5 秒，只有 ack 且未被退回才把 Outbox 行标成已发送；定时任务 relay
 * 默认每 5 秒重扫待发送行补发。行没有失败次数和放弃状态，确认不了的消息会一直重试。
 *
 * <p>afterCommit 线程与定时任务可能同时取到同一行并发送两次，消费端按事件 ID 幂等去重，
 * 重复投递不产生副作用。
 */
@Component
@ConditionalOnProperty(name = "gcfeed.rabbitmq.enabled", havingValue = "true")
public class OutboxFeedEventPublisher implements FeedEventPublisher {

    public static final String VIDEO_PUBLISHED = "video.published";
    public static final String RELATION_CHANGED = "relation.changed";

    private static final Logger log = LoggerFactory.getLogger(OutboxFeedEventPublisher.class);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public OutboxFeedEventPublisher(OutboxRepository outbox, RabbitTemplate rabbit, JsonMapper json) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        // mandatory 配合 publisher-returns：消息路由不到任何队列时退回发送方，而不是被代理悄悄丢弃。
        rabbit.setMandatory(true);
    }

    @Override
    public void videoPublished(VideoPublishedEvent event) {
        enqueue(event.eventId(), VIDEO_PUBLISHED, json.writeValueAsString(event));
    }

    @Override
    public void relationChanged(RelationChangedEvent event) {
        enqueue(event.eventId(), RELATION_CHANGED, json.writeValueAsString(event));
    }

    /** 定时补发：afterCommit 那次发送失败的消息行仍是待发送，这里按固定节奏重扫。 */
    @Scheduled(fixedDelayString = "${gcfeed.rabbitmq.relay-interval-ms:5000}")
    public void relay() {
        publishPending();
    }

    public void publishPending() {
        for (EventOutbox row : outbox.pending(100)) {
            publish(row);
        }
    }

    private void enqueue(String eventId, String type, String payload) {
        // 待发送行与业务写入处于同一事务：业务回滚时这行一起消失，不会发出指向不存在数据的消息。
        outbox.insertPending(eventId, type, payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 注册 afterCommit 而不是立即发送：提交成功才发，消费者读到的一定是已提交的数据。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishPending();
                }
            });
        } else {
            // 无事务上下文（如测试直接调用）时行已插入，立即发送即可。
            publishPending();
        }
    }

    private void publish(EventOutbox row) {
        try {
            // 事件类型决定去向：relation.changed 走关系交换机，其余（当前只有视频发布）走视频交换机。
            String exchange = RELATION_CHANGED.equals(row.getEventType())
                    ? RabbitTopology.RELATION_EXCHANGE : RabbitTopology.VIDEO_EXCHANGE;
            String routingKey = RELATION_CHANGED.equals(row.getEventType())
                    ? RabbitTopology.RELATION_CHANGED : RabbitTopology.VIDEO_PUBLISHED;
            CorrelationData confirmation = new CorrelationData(row.getEventId());
            rabbit.convertAndSend(exchange, routingKey, row.getPayload(), message -> {
                message.getMessageProperties().setContentType("application/json");
                // messageId 带事件 ID：排查问题时用它对上 Outbox 行、消息和消费端回执。
                message.getMessageProperties().setMessageId(row.getEventId());
                return message;
            }, confirmation);
            // 同步等确认最多 5 秒：拿到 broker 的 ack 才算发送完成，行才能标成已发送。
            CorrelationData.Confirm confirm = confirmation.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck() || confirmation.getReturned() != null) {
                // 被退回说明路由不到队列，与 nack 同等对待：行保持待发送，交给补发任务。
                throw new IllegalStateException("rabbit publish was not confirmed");
            }
            outbox.markSent(row.getId());
        } catch (Exception ex) {
            // 任何失败只记日志：发布、关注接口不能因 MQ 故障对用户报错，行等定时任务补发。
            log.warn("Outbox 发送失败，等待补发 eventId={}: {}", row.getEventId(), ex.toString());
        }
    }
}
