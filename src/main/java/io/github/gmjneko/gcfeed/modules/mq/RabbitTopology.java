package io.github.gmjneko.gcfeed.modules.mq;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时声明持久化交换机和队列。业务请求里不再临时建拓扑。
 *
 * <p>两个业务交换机按领域划分：gcfeed.video 承载视频发布事件，gcfeed.relation 承载关注关系变化。
 * video.published 一条消息同时绑定两个队列（关注流扇出、向量生成），topic 交换机把它复制成两份，
 * 两条链路互不等待。gcfeed.dlx 是死信交换机：业务队列里被拒绝且不重回队列的消息
 * 按各自的死信路由键进入 原队列名.dlq，由 FeedMessageListener 落库留档。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gcfeed.rabbitmq.enabled", havingValue = "true")
public class RabbitTopology {

    public static final String VIDEO_EXCHANGE = "gcfeed.video";
    public static final String RELATION_EXCHANGE = "gcfeed.relation";
    public static final String DEAD_EXCHANGE = "gcfeed.dlx";
    public static final String VIDEO_PUBLISHED = "video.published";
    public static final String RELATION_CHANGED = "relation.changed";
    public static final String PUBLISHED_QUEUE = "gcfeed.video.published";
    public static final String EMBEDDING_QUEUE = "gcfeed.video.embedding";
    public static final String RELATION_QUEUE = "gcfeed.relation.changed";

    @Bean
    public Declarables feedDeclarables() {
        // 交换机与队列都声明为持久化：代理重启后拓扑仍在，只需要补消息本身。
        TopicExchange video = new TopicExchange(VIDEO_EXCHANGE, true, false);
        TopicExchange relation = new TopicExchange(RELATION_EXCHANGE, true, false);
        TopicExchange dead = new TopicExchange(DEAD_EXCHANGE, true, false);
        Queue published = durable(PUBLISHED_QUEUE, VIDEO_PUBLISHED);
        Queue embedding = durable(EMBEDDING_QUEUE, "video.embedding");
        Queue relationQueue = durable(RELATION_QUEUE, RELATION_CHANGED);
        Queue publishedDead = QueueBuilder.durable(PUBLISHED_QUEUE + ".dlq").build();
        Queue embeddingDead = QueueBuilder.durable(EMBEDDING_QUEUE + ".dlq").build();
        Queue relationDead = QueueBuilder.durable(RELATION_QUEUE + ".dlq").build();
        return new Declarables(
                video, relation, dead,
                published, embedding, relationQueue, publishedDead, embeddingDead, relationDead,
                BindingBuilder.bind(published).to(video).with(VIDEO_PUBLISHED),
                BindingBuilder.bind(embedding).to(video).with(VIDEO_PUBLISHED),
                BindingBuilder.bind(relationQueue).to(relation).with(RELATION_CHANGED),
                BindingBuilder.bind(publishedDead).to(dead).with(VIDEO_PUBLISHED),
                BindingBuilder.bind(embeddingDead).to(dead).with("video.embedding"),
                BindingBuilder.bind(relationDead).to(dead).with(RELATION_CHANGED));
    }

    /** 业务队列统一挂死信交换机：被拒绝且不重回队列的消息按死信路由键进入对应的 原队列名.dlq。 */
    private static Queue durable(String name, String deadKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(deadKey)
                .build();
    }
}
