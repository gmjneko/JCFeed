package io.github.gmjneko.gcfeed.modules.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 同一条消息失败达到上限后写入死信并停止重入，避免毒消息打满队列。
 *
 * <p>幂等与计数都委托 EventReceipts：done 拦截已处理事件，nextAttempt 累计失败次数。
 * 达到 maxAttempts 后写死信表并把事件标记为完成，异常不再抛出——监听器正常返回、
 * 消息被确认，这就是「停止重入」的实现。未达上限时先按次数线性退避，再把异常抛回容器，
 * 由代理侧的拒绝语义（当前配置为不重回队列）决定消息去向。
 */
@Component
public class DeliveryRetry {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetry.class);

    private final EventReceipts receipts;
    private final DeadLetterStore deadLetters;
    private final RabbitSettings settings;
    private final Delay delay;

    @Autowired
    public DeliveryRetry(EventReceipts receipts, DeadLetterStore deadLetters, RabbitSettings settings) {
        this(receipts, deadLetters, settings, attempt -> {
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    DeliveryRetry(EventReceipts receipts, DeadLetterStore deadLetters, RabbitSettings settings, Delay delay) {
        this.receipts = receipts;
        this.deadLetters = deadLetters;
        this.settings = settings;
        this.delay = delay;
    }

    public void run(String consumer, String eventId, String taskType, String payload, Runnable action) {
        // 事件 ID 缺失或该消费者已处理过：直接返回让容器 ack，重复投递在这里被挡住。
        if (eventId == null || eventId.isBlank() || receipts.done(consumer, eventId)) {
            return;
        }
        try {
            action.run();
            // 成功后才写回执：此后同一条消息（补发重复、死信重放）都不会再执行动作。
            receipts.markDone(consumer, eventId);
        } catch (RuntimeException ex) {
            int attempt = receipts.nextAttempt(consumer, eventId);
            if (attempt >= settings.maxAttempts()) {
                // 超过上限：写死信并标记完成，不再抛出——监听器正常结束、消息被确认，链路到此为止。
                deadLetters.record(taskType, payload, attempt, ex.getMessage());
                receipts.markDone(consumer, eventId);
                log.warn("消息超过重试次数，已写入死信 consumer={} eventId={} attempt={}", consumer, eventId, attempt);
                return;
            }
            // 线性退避后把异常抛回容器，由代理侧决定去向（当前配置是进死信队列）。
            delay.wait(attempt);
            throw ex;
        }
    }

    @FunctionalInterface
    interface Delay {
        void wait(int attempt);
    }
}
