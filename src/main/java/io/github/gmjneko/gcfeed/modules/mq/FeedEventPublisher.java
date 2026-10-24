package io.github.gmjneko.gcfeed.modules.mq;

import io.github.gmjneko.gcfeed.modules.embedding.service.VideoPublishedEvent;

/**
 * 发布已经提交的领域事件。关闭 MQ 时只在进程内分发，启用时写入 Outbox。
 *
 * <p>两个入口（发布视频、关注/取关）都在业务事务内调用；实现负责保证「事件只在提交后生效」：
 * Outbox 实现先落 event_outbox、afterCommit 后再发送，进程内实现靠 AFTER_COMMIT 监听器。
 * 事件类型与传输解耦，调用方完全不感知消息走 Spring 事件还是 RabbitMQ。
 */
public interface FeedEventPublisher {

    void videoPublished(VideoPublishedEvent event);

    void relationChanged(RelationChangedEvent event);
}
