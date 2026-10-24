package io.github.gmjneko.gcfeed.modules.mq;

/**
 * 消费端去重和失败次数。成功或进入死信后再次投递不再执行。
 *
 * <p>键里带 consumer 名：同一条发布消息被关注流和向量两个消费者各处理一次，回执互不覆盖。
 */
public interface EventReceipts {

    boolean done(String consumer, String eventId);

    int nextAttempt(String consumer, String eventId);

    void markDone(String consumer, String eventId);
}
