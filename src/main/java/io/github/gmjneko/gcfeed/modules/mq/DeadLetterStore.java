package io.github.gmjneko.gcfeed.modules.mq;

/** 超过重试次数后保存原始消息，避免毒消息反复入队。写入方有 DeliveryRetry（达到上限）和 .dlq 监听器两处。 */
public interface DeadLetterStore {

    void record(String taskType, String payload, int retryCount, String lastError);
}
