package io.github.gmjneko.gcfeed.modules.feed.hot;

import io.github.gmjneko.gcfeed.common.constant.RedisKeyConstant;
import io.github.gmjneko.gcfeed.modules.feed.support.SingleFlight;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 用 Redis 分钟桶保存近一小时热度。窗口在第一次读取时合并，并在翻页期间保持不变。
 *
 * <p>写入累进“当前 UTC 分钟”的桶，读取把锚定分钟往回数的 60 个桶并成一份快照。
 * 快照键含 window_end，每过一分钟换新键，榜单的刷新粒度就是一分钟；
 * 合并是惰性的，只有有人读的第一刻才执行，没人看的分钟不花并集成本。
 */
@Component
public class RedisHotRanking implements HotRanking {

    private static final Logger log = LoggerFactory.getLogger(RedisHotRanking.class);

    private final RedissonClient redis;
    private final Clock clock;
    /** 合并同机对同一窗口的并发重建；跨实例的重复合并输入相同、结果一致，只是多算一次并集。 */
    private final SingleFlight<Boolean> rebuilds = new SingleFlight<>();

    public RedisHotRanking(RedissonClient redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void add(long videoId, int scoreDelta) {
        // 零增量也会把 member 写成 0 分占住桶，和非法 ID 一起挡在门外。
        if (videoId <= 0 || scoreDelta == 0) {
            return;
        }
        try {
            // 互动固定落在“当前这一分钟”的桶里，60 个桶相加拼出近一小时滑动窗口。
            String key = HotWindows.minuteKey(HotWindows.minute(clock.instant()));
            RScoredSortedSet<String> bucket = buckets(key);
            bucket.addScore(HotWindows.member(videoId), scoreDelta);
            // 窗口回看最多 60 个桶，两小时 TTL 留足余量，到期由 Redis 自然清理。
            bucket.expire(Duration.ofSeconds(RedisKeyConstant.TTL_FEED_HOT_MINUTE_SECONDS));
        } catch (RuntimeException ex) {
            log.warn("写入热榜分钟桶失败，互动结果保持有效 videoId={} delta={}: {}", videoId, scoreDelta, ex.toString());
        }
    }

    @Override
    public HotPage page(LocalDateTime windowEnd, int offset, int limit) {
        if (limit <= 0) {
            return HotPage.ready(List.of());
        }
        int start = Math.max(offset, 0);
        try {
            LocalDateTime end = windowEnd.truncatedTo(ChronoUnit.MINUTES);
            RScoredSortedSet<String> window = buckets(HotWindows.windowKey(end));
            if (!window.isExists()) {
                // 双重检查：SingleFlight 等待期间别的请求可能已经把窗口建好，
                // 拿到执行权后再读一次，避免重复执行并集。
                rebuilds.call(HotWindows.windowKey(end), () -> {
                    if (!window.isExists()) {
                        rebuild(window, end);
                    }
                    return Boolean.TRUE;
                });
            }
            // 窗口是不可变快照：建好只读、到期自灭，在它上面按 offset 翻页不会漂移。
            Collection<ScoredEntry<String>> entries = window.entryRangeReversed(start, start + limit - 1);
            List<Long> ids = new ArrayList<>(entries.size());
            for (ScoredEntry<String> entry : entries) {
                // member 必须是 20 位零填充数字，格式不对的脏数据只丢这一条，不让整页失败。
                Long videoId = HotWindows.videoId(entry.getValue());
                if (videoId != null) {
                    ids.add(videoId);
                }
            }
            return HotPage.ready(ids);
        } catch (RuntimeException ex) {
            // 只有异常代表 Redis 不可用；空榜在上面以 ready(空列表) 返回，两种语义由策略层区分。
            return HotPage.unavailable(ex);
        }
    }

    private void rebuild(RScoredSortedSet<String> window, LocalDateTime windowEnd) {
        // 含结束分钟在内的 60 个桶求并集：同一视频散在多个桶里的增量按 SUM 相加成窗口总热度。
        String[] minutes = HotWindows.minutes(windowEnd).stream()
                .map(HotWindows::minuteKey)
                .toArray(String[]::new);
        window.union(RScoredSortedSet.Aggregate.SUM, minutes);
        // 取消赞等负增量会把热度加回去，净分为零或负的视频不该出现在榜上。
        window.removeRangeByScore(Double.NEGATIVE_INFINITY, true, 0, true);
        // 快照两分钟自灭，没人翻页的窗口自动回收；需要时按同样的输入重合并即可，内容不变。
        window.expire(Duration.ofSeconds(RedisKeyConstant.TTL_FEED_HOT_WINDOW_SECONDS));
    }

    private RScoredSortedSet<String> buckets(String key) {
        return redis.getScoredSortedSet(key, StringCodec.INSTANCE);
    }
}
