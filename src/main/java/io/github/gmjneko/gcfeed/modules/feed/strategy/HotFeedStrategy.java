package io.github.gmjneko.gcfeed.modules.feed.strategy;

import io.github.gmjneko.gcfeed.common.api.ErrorCode;
import io.github.gmjneko.gcfeed.common.exception.BusinessException;
import io.github.gmjneko.gcfeed.modules.feed.hot.HotRanking;
import io.github.gmjneko.gcfeed.modules.feed.hot.HotWindows;
import io.github.gmjneko.gcfeed.modules.feed.model.FeedScene;
import io.github.gmjneko.gcfeed.modules.feed.model.HotCursor;
import io.github.gmjneko.gcfeed.modules.feed.vo.FeedPageVO;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 热榜：近一小时热度榜，匿名可看。排名完全由 Redis 分钟桶合并出的窗口提供；
 * Redis 不可用时返回 503，不以不同口径的数据替代热榜。
 */
@Component
public class HotFeedStrategy implements FeedStrategy {

    private static final Logger log = LoggerFactory.getLogger(HotFeedStrategy.class);

    private final HotRanking hotRanking;
    private final FeedCardRenderer renderer;
    private final Clock clock;
    private final JsonMapper json;

    public HotFeedStrategy(HotRanking hotRanking, FeedCardRenderer renderer, Clock clock, JsonMapper json) {
        this.hotRanking = hotRanking;
        this.renderer = renderer;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public FeedScene scene() {
        return FeedScene.HOT;
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public FeedPageVO list(FeedQuery query) {
        return hot(query.cursor(), query.limit(), query.viewerId());
    }

    private FeedPageVO hot(String cursor, int limit, Long viewerId) {
        HotCursor hotCursor = HotCursor.decode(cursor, json);
        // 第一页以当前 UTC 分钟为窗口结束；翻页沿用游标里的 window_end，
        // 保证翻到一半窗口滑走也不会重复或漏掉。
        LocalDateTime windowEnd = HotWindows.minute(clock.instant());
        int offset = 0;
        if (hotCursor != null) {
            windowEnd = hotCursor.windowEnd();
            offset = hotCursor.offset();
        }
        // 多取一条（limit + 1）判断 has_more，与时间线同一套约定。
        HotRanking.HotPage ranked = hotRanking.page(windowEnd, offset, limit + 1);
        if (!ranked.available()) {
            // 空榜是合法结果，只有 Redis 抛异常才走降级，不能把空列表当成 Redis 坏了。
            if (ranked.failure() != null) {
                log.warn("Redis 热榜不可用", ranked.failure());
                throw new BusinessException(ErrorCode.HOT_RANKING_UNAVAILABLE, ranked.failure());
            }
            throw new BusinessException(ErrorCode.HOT_RANKING_UNAVAILABLE);
        }
        boolean hasMore = ranked.videoIds().size() > limit;
        List<Long> pageIds = hasMore ? ranked.videoIds().subList(0, limit) : ranked.videoIds();
        // 热榜只给出 ID 顺序，作者和发布时间交给渲染层兜底补全。
        List<PageSlot> slots = pageIds.stream().map(id -> new PageSlot(id, null, null)).toList();
        int nextOffset = offset + pageIds.size();
        String next = pageIds.isEmpty() ? "" : new HotCursor(windowEnd, nextOffset).encode(json);
        return new FeedPageVO(FeedScene.HOT.getValue(), renderer.render(slots, viewerId, Map.of()), next, hasMore);
    }

}
