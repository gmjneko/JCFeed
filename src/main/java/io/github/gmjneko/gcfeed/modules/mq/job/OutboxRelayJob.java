package io.github.gmjneko.gcfeed.modules.mq.job;

import io.github.gmjneko.gcfeed.modules.mq.OutboxFeedEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 补发任务：定时扫描待发送行，复用与 afterCommit 相同的发送逻辑。
 *
 * <p>兜住的窗口：业务事务已提交、但 afterCommit 快路径没有完成（发送失败或进程退出），
 * 消息行停在待发送状态。这里默认每 5 秒重扫一次，直到 RabbitMQ 恢复把积压补完。
 * 与 EmbeddingBackfillJob 一样只做调度入口，实际的发送、确认和标记在 OutboxFeedEventPublisher。
 *
 * <p>失败不计数不放弃：确认不了的行每轮重试。多实例部署时每个实例都会跑这份扫描，
 * 重复发送由消费端按事件 ID 幂等去重；需要单写者时可给本类单独加开关或选主。
 */
@Component
@ConditionalOnProperty(name = "gcfeed.rabbitmq.enabled", havingValue = "true")
public class OutboxRelayJob {

    private final OutboxFeedEventPublisher publisher;

    public OutboxRelayJob(OutboxFeedEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${gcfeed.rabbitmq.relay-interval-ms:5000}")
    public void relay() {
        publisher.publishPending();
    }
}
