package io.github.gmjneko.gcfeed.modules.mq;

import io.github.gmjneko.gcfeed.modules.embedding.service.EmbeddingService;
import io.github.gmjneko.gcfeed.modules.embedding.service.VideoPublishedEvent;
import io.github.gmjneko.gcfeed.modules.feed.following.RelationIndexService;
import io.github.gmjneko.gcfeed.modules.feed.following.VideoFanoutService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 启用 MQ 时消费发布扇出、向量和关注关系。幂等与失败处理统一交给 DeliveryRetry。
 *
 * <p>监听方法正常返回即 ack。抛出异常时容器按 default-requeue-rejected=false 拒绝消息
 * 且不重回原队列，消息经死信交换机进入对应的 .dlq，再由 dead() 落库留档——大多数失败
 * 在第一次就会离开原队列。DeliveryRetry 的失败计数针对同一事件被重复投递的场景
 * （如 Outbox 的 afterCommit 线程与补发任务并发发送），达到上限后不再执行也不抛出。
 */
@Component
@ConditionalOnProperty(name = "gcfeed.rabbitmq.enabled", havingValue = "true")
public class FeedMessageListener {

    private final JsonMapper json;
    private final DeliveryRetry retry;
    private final VideoFanoutService fanout;
    private final EmbeddingService embeddings;
    private final RelationIndexService relations;
    private final DeadLetterStore deadLetters;

    public FeedMessageListener(JsonMapper json, DeliveryRetry retry, VideoFanoutService fanout,
                               EmbeddingService embeddings, RelationIndexService relations,
                               DeadLetterStore deadLetters) {
        this.json = json;
        this.retry = retry;
        this.fanout = fanout;
        this.embeddings = embeddings;
        this.relations = relations;
        this.deadLetters = deadLetters;
    }

    /** 关注流扇出：把新发布的视频写进粉丝收件箱或大 V 发件箱（VideoFanoutService）。 */
    @RabbitListener(queues = RabbitTopology.PUBLISHED_QUEUE)
    public void fanout(String body) {
        VideoPublishedEvent event = json.readValue(body, VideoPublishedEvent.class);
        retry.run("video-fanout", event.eventId(), OutboxFeedEventPublisher.VIDEO_PUBLISHED, body,
                () -> fanout.onPublished(event));
    }

    /** 向量生成：与 fanout 消费同一条发布消息的两份副本，回执键带各自消费者名，互不干扰。 */
    @RabbitListener(queues = RabbitTopology.EMBEDDING_QUEUE)
    public void embedding(String body) {
        VideoPublishedEvent event = json.readValue(body, VideoPublishedEvent.class);
        retry.run("video-embedding", event.eventId(), "video.embedding", body,
                () -> embeddings.generateForPublishedVideo(event));
    }

    /** 关注关系变化：新关注回填收件箱，取关移除该作者的视频（RelationIndexService）。 */
    @RabbitListener(queues = RabbitTopology.RELATION_QUEUE)
    public void relation(String body) {
        RelationChangedEvent event = json.readValue(body, RelationChangedEvent.class);
        retry.run("relation-index", event.eventId(), OutboxFeedEventPublisher.RELATION_CHANGED, body,
                () -> relations.onChanged(event));
    }

    /** 死信兜底落库：不管 DeliveryRetry 内部计数走到哪，进了 .dlq 的消息都会在这里留下记录。 */
    @RabbitListener(queues = {
            RabbitTopology.PUBLISHED_QUEUE + ".dlq",
            RabbitTopology.EMBEDDING_QUEUE + ".dlq",
            RabbitTopology.RELATION_QUEUE + ".dlq"
    })
    public void dead(Message message) {
        // taskType 用来源队列名，可追溯是哪条链路死的信；重试次数没有原始上下文，固定记 3。
        String type = message.getMessageProperties().getConsumerQueue();
        deadLetters.record(type == null ? "dead-letter" : type,
                new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                3, "rejected to dead letter queue");
    }
}
