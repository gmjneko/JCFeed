package io.github.gmjneko.gcfeed.modules.feed.hot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 近一小时热榜。读取失败时返回 {@link HotPage#unavailable()}；空列表是合法的空榜，不能当成 Redis 故障。
 */
public interface HotRanking {

    int LIKE_WEIGHT = 3;
    int FAVORITE_WEIGHT = 4;
    int COMMENT_WEIGHT = 5;

    /** 把一次互动热度加进当前 UTC 分钟桶。失败只记录日志，不抛给已经提交的互动事务。 */
    void add(long videoId, int scoreDelta);

    /**
     * 读取锚定窗口中的一页视频 ID，按热度倒序。
     *
     * @param limit 调用方传入 {@code pageSize + 1}，多出的一条用来判断还有没有下一页
     */
    HotPage page(LocalDateTime windowEnd, int offset, int limit);

    record HotPage(boolean available, List<Long> videoIds, RuntimeException failure) {

        public static HotPage ready(List<Long> videoIds) {
            return new HotPage(true, List.copyOf(videoIds), null);
        }

        public static HotPage unavailable(RuntimeException failure) {
            return new HotPage(false, List.of(), failure);
        }
    }
}
