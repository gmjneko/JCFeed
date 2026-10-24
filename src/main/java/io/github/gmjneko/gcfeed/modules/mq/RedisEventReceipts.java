package io.github.gmjneko.gcfeed.modules.mq;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 记录消费回执。Redis 不可用时退回本机计数，至少让当前进程能停在最大重试次数。
 *
 * <p>回执 7 天过期：幂等窗口盖住 Outbox 补发的重试期，键也不会无限堆积。
 * markDone 先写本机内存再写 Redis：Redis 写失败时本进程不再重复处理，其他实例可能
 * 再消费一次——下游写入都是覆盖语义，重复消费不产生脏数据。
 */
@Component
public class RedisEventReceipts implements EventReceipts {

    private static final Logger log = LoggerFactory.getLogger(RedisEventReceipts.class);
    private static final Duration TTL = Duration.ofDays(7);

    private final RedissonClient redis;
    private final ConcurrentHashMap<String, AtomicInteger> localAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> localDone = new ConcurrentHashMap<>();

    public RedisEventReceipts(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public boolean done(String consumer, String eventId) {
        String key = doneKey(consumer, eventId);
        // 本机命中直接返回，省一次 Redis 往返。
        if (localDone.containsKey(key)) {
            return true;
        }
        try {
            RBucket<String> bucket = redis.getBucket(key, StringCodec.INSTANCE);
            return bucket != null && bucket.isExists();
        } catch (RuntimeException ex) {
            // 读失败按未完成处理：宁可下游多执行一次（写入都是覆盖语义），也不漏掉该做的动作。
            log.warn("读取消费回执失败，按未完成处理 key={}: {}", key, ex.toString());
            return false;
        }
    }

    @Override
    public int nextAttempt(String consumer, String eventId) {
        String key = attemptKey(consumer, eventId);
        try {
            RAtomicLong counter = redis.getAtomicLong(key);
            // 自增后刷新 TTL：反复失败期间键保持存活，长期不再出现的事件键自然过期。
            long value = counter.incrementAndGet();
            counter.expire(TTL);
            return (int) Math.min(value, Integer.MAX_VALUE);
        } catch (RuntimeException ex) {
            // Redis 不可用退回本机计数：计数只在当前进程有效，但至少本进程能停在最大重试次数。
            log.warn("记录消费重试次数失败，改用本机计数 key={}: {}", key, ex.toString());
            return localAttempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Override
    public void markDone(String consumer, String eventId) {
        String key = doneKey(consumer, eventId);
        // 先本机后 Redis 的顺序：Redis 写失败时本进程已视为完成，当前进程不会重复消费。
        localDone.put(key, Boolean.TRUE);
        try {
            RBucket<String> bucket = redis.getBucket(key, StringCodec.INSTANCE);
            if (bucket != null) {
                bucket.set("1", TTL);
            }
        } catch (RuntimeException ex) {
            log.warn("写入消费回执失败 key={}: {}", key, ex.toString());
        }
    }

    private static String doneKey(String consumer, String eventId) {
        return "mq:receipt:v1:" + consumer + ":" + eventId;
    }

    private static String attemptKey(String consumer, String eventId) {
        return "mq:attempt:v1:" + consumer + ":" + eventId;
    }
}
