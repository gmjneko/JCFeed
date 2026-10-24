package io.github.gmjneko.gcfeed.modules.mq;

import io.github.gmjneko.gcfeed.modules.embedding.service.VideoPublishedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 测试和未启用 MQ 时保留原来的进程内发布，向量监听器仍在提交后执行。
 *
 * <p>relationChanged 在这里是空实现：收件箱只由 MQ 消费者维护，关队列时关注流读不到索引，
 * 会退回数据库查询路径，功能仍在，只是不走 Redis 加速。
 */
@Component
@ConditionalOnProperty(name = "gcfeed.rabbitmq.enabled", havingValue = "false", matchIfMissing = true)
public class LocalFeedEventPublisher implements FeedEventPublisher {

    private final ApplicationEventPublisher events;

    public LocalFeedEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void videoPublished(VideoPublishedEvent event) {
        events.publishEvent(event);
    }

    @Override
    public void relationChanged(RelationChangedEvent event) {
        // 收件箱由 MQ 消费者维护。未启用队列时关注流继续查数据库。
    }
}
