package io.github.gmjneko.gcfeed.modules.feed.hot;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 与 Redis 分钟桶相同的窗口规则，供不连接 Redis 的测试控制热度和故障。 */
public class MemoryHotRanking implements HotRanking {

    private final Clock clock;
    private final Map<LocalDateTime, Map<Long, Double>> buckets = new HashMap<>();
    private boolean open = true;

    public MemoryHotRanking(Clock clock) {
        this.clock = clock;
    }

    public void setAvailable(boolean open) {
        this.open = open;
    }

    public void clear() {
        buckets.clear();
        open = true;
    }

    public double score(long videoId, LocalDateTime windowEnd) {
        double total = 0;
        for (LocalDateTime minute : HotWindows.minutes(windowEnd)) {
            total += buckets.getOrDefault(minute.truncatedTo(ChronoUnit.MINUTES), Map.of())
                    .getOrDefault(videoId, 0.0);
        }
        return total;
    }

    @Override
    public void add(long videoId, int scoreDelta) {
        if (videoId <= 0 || scoreDelta == 0) {
            return;
        }
        LocalDateTime minute = HotWindows.minute(clock.instant());
        buckets.computeIfAbsent(minute, ignored -> new HashMap<>()).merge(videoId, (double) scoreDelta, Double::sum);
    }

    @Override
    public HotPage page(LocalDateTime windowEnd, int offset, int limit) {
        if (!open) {
            return HotPage.unavailable(null);
        }
        if (limit <= 0) {
            return HotPage.ready(List.of());
        }
        Map<Long, Double> merged = new HashMap<>();
        for (LocalDateTime minute : HotWindows.minutes(windowEnd)) {
            buckets.getOrDefault(minute.truncatedTo(ChronoUnit.MINUTES), Map.of())
                    .forEach((videoId, score) -> merged.merge(videoId, score, Double::sum));
        }
        List<Long> ordered = merged.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
        int start = Math.max(offset, 0);
        if (start >= ordered.size()) {
            return HotPage.ready(List.of());
        }
        return HotPage.ready(new ArrayList<>(ordered.subList(start, Math.min(ordered.size(), start + limit))));
    }
}
