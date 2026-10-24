package io.github.gmjneko.gcfeed.modules.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 关注流分发使用的 RabbitMQ 开关和推拉参数。连接地址在 spring.rabbitmq 下。
 *
 * <p>enabled 是总开关：true 走 Outbox 加队列的完整链路，false 退回进程内事件（LocalFeedEventPublisher）。
 * 其余参数分三组：relayIntervalMs 决定 Outbox 补发节奏；bigVFollowerThreshold、followerBatchSize、
 * inboxMaxLen、outboxMaxLen、backfillLimit 属于推拉结合的关注流索引；maxAttempts 是消费端放弃重试的上限。
 */
@ConfigurationProperties(prefix = "gcfeed.rabbitmq")
public record RabbitSettings(
        boolean enabled,
        long relayIntervalMs,
        int bigVFollowerThreshold,
        int followerBatchSize,
        int inboxMaxLen,
        int outboxMaxLen,
        int backfillLimit,
        int maxAttempts
) {
    public RabbitSettings {
        // 配置缺失或写成非正数时回落默认值，与 application.yaml 的推荐值一致，避免带非法参数运行。
        if (relayIntervalMs <= 0) {
            relayIntervalMs = 5000;
        }
        if (bigVFollowerThreshold <= 0) {
            bigVFollowerThreshold = 10000;
        }
        if (followerBatchSize <= 0) {
            followerBatchSize = 500;
        }
        if (inboxMaxLen <= 0) {
            inboxMaxLen = 1000;
        }
        if (outboxMaxLen <= 0) {
            outboxMaxLen = 500;
        }
        if (backfillLimit <= 0) {
            backfillLimit = 100;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
    }
}
