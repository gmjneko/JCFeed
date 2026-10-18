package io.github.gmjneko.gcfeed.modules.feed.strategy;

import io.github.gmjneko.gcfeed.modules.feed.cache.CachedVideoStat;
import io.github.gmjneko.gcfeed.modules.feed.cache.FeedCache;
import io.github.gmjneko.gcfeed.modules.feed.cache.FeedCardCache;
import io.github.gmjneko.gcfeed.modules.feed.model.FeedCursor;
import io.github.gmjneko.gcfeed.modules.feed.model.FeedScene;
import io.github.gmjneko.gcfeed.modules.feed.model.HotCursor;
import io.github.gmjneko.gcfeed.modules.feed.repository.FeedRepository;
import io.github.gmjneko.gcfeed.modules.feed.vo.FeedItemVO;
import io.github.gmjneko.gcfeed.modules.feed.vo.FeedPageVO;
import io.github.gmjneko.gcfeed.modules.interaction.entity.InteractionAction;
import io.github.gmjneko.gcfeed.modules.user.entity.User;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import io.github.gmjneko.gcfeed.modules.video.entity.VideoStat;
import io.github.gmjneko.gcfeed.modules.video.model.VideoStatField;
import io.github.gmjneko.gcfeed.modules.video.service.VideoStatDeltaBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四种场景共用的卡片装配。缓存按变化频率分层：卡片内容 15 分钟、计数 15 秒、
 * 列表顺序由各策略自己的页缓存控制；点赞、收藏和关注状态属于个人数据，
 * 不进共享缓存，每次按当前用户实时查询，避免 A 的状态显示到 B 的列表上。
 */
@Component
public class FeedCardRenderer {

    private static final Logger log = LoggerFactory.getLogger(FeedCardRenderer.class);

    private final FeedRepository feedRepository;
    private final FeedCache feedCache;
    private final VideoStatDeltaBuffer statBuffer;
    private final JsonMapper json;

    public FeedCardRenderer(FeedRepository feedRepository, FeedCache feedCache,
                            VideoStatDeltaBuffer statBuffer, JsonMapper json) {
        this.feedRepository = feedRepository;
        this.feedCache = feedCache;
        this.statBuffer = statBuffer;
        this.json = json;
    }

    /**
     * 把视频列表组装成带时间/ID 游标的一页，供关注流数据库路径使用。
     */
    public FeedPageVO timePage(FeedScene scene, List<Video> videos, int limit, Long viewerId) {
        if (videos.isEmpty()) {
            // 空结果是合法页：游标为空串、没有下一页，不与调用方约定 null。
            return new FeedPageVO(scene.getValue(), List.of(), "", false);
        }
        boolean hasMore = videos.size() > limit;
        List<Video> page = hasMore ? videos.subList(0, limit) : videos;
        Video last = page.getLast();
        String next = new FeedCursor(last.getPublishedAt(), last.getId()).encode();
        return new FeedPageVO(scene.getValue(), render(slots(page), viewerId, index(page)), next, hasMore);
    }

    /**
     * 渲染槽位为卡片列表，输入的槽位顺序就是最终展示顺序，输出必须保持。
     * 装配顺序：卡片缓存 -> 已知视频 -> 批量查库；计数控存；最后叠加个人状态。
     */
    public List<FeedItemVO> render(List<PageSlot> slots, Long viewerId, Map<Long, Video> known) {
        if (slots.isEmpty()) {
            return List.of();
        }
        // 同一次请求里同一视频可能出现多次（理论上），去重后批量读缓存。
        List<Long> videoIds = slots.stream().map(PageSlot::videoId).distinct().toList();
        Map<Long, FeedCardCache> cards = new HashMap<>(feedCache.findCards(videoIds));
        List<FeedCardCache> missingCards = new ArrayList<>();
        List<Video> loaded = new ArrayList<>();
        List<Long> unresolved = new ArrayList<>();
        // 卡片未命中的视频分两路：本页查库已带出的直接用（known），
        // 其余（如热榜只给了 ID）才需要回库，减少一次 findPublishedByIds。
        for (Long videoId : videoIds) {
            if (cards.containsKey(videoId)) {
                continue;
            }
            Video video = known.get(videoId);
            if (video == null) {
                unresolved.add(videoId);
            } else {
                loaded.add(video);
            }
        }
        if (!unresolved.isEmpty()) {
            // findPublishedByIds 只返回上线视频：下架或已删除的在这里自然被过滤掉。
            loaded.addAll(feedRepository.findPublishedByIds(unresolved));
        }
        if (!loaded.isEmpty()) {
            // 作者资料按整批作者 ID 一次查出，避免循环内逐个访问数据库。
            Map<Long, User> authors = feedRepository.findAuthors(loaded.stream().map(Video::getAuthorId).distinct().toList());
            for (Video video : loaded) {
                User author = authors.get(video.getAuthorId());
                // 作者缺失或已注销时昵称、头像为空，卡片仍然可以展示视频本身。
                FeedCardCache card = new FeedCardCache(video.getId(), video.getAuthorId(),
                        author == null ? null : author.getNickname(),
                        author == null ? null : author.getAvatarUrl(),
                        video.getTitle(), video.getDescription(), video.getMediaUrl(), video.getCoverUrl(),
                        video.getPublishedAt());
                cards.put(video.getId(), card);
                missingCards.add(card);
            }
            feedCache.saveCards(missingCards);
        }
        // 计数独立于卡片缓存：卡片 15 分钟，计数 15 秒，两者过期节奏不同。
        Map<Long, CachedVideoStat> stats = stats(videoIds);
        // 收集作者 ID 时卡片优先、槽位兜底：热榜路径槽位里只有视频 ID。
        Set<Long> authorIds = new HashSet<>();
        for (PageSlot slot : slots) {
            FeedCardCache card = cards.get(slot.videoId());
            if (card != null) {
                authorIds.add(card.authorId());
            } else if (slot.authorId() != null) {
                authorIds.add(slot.authorId());
            }
        }
        // 关注状态查询对匿名用户也安全：Repository 对 null viewerId 返回空集合。
        Set<Long> followedAuthors = feedRepository.findFollowedAuthors(viewerId, List.copyOf(authorIds));
        Set<Long> liked = new HashSet<>();
        Set<Long> favorited = new HashSet<>();
        if (viewerId != null) {
            // 点赞、收藏状态只对登录用户查询，匿名用户两项都是 false。
            for (InteractionAction action : feedRepository.findViewerActions(viewerId, videoIds)) {
                if (InteractionAction.LIKE.equals(action.getActionType())) {
                    liked.add(action.getVideoId());
                } else if (InteractionAction.FAVORITE.equals(action.getActionType())) {
                    favorited.add(action.getVideoId());
                }
            }
        }
        List<FeedItemVO> items = new ArrayList<>(slots.size());
        for (PageSlot slot : slots) {
            FeedCardCache card = cards.get(slot.videoId());
            if (card == null) {
                // 卡片仍缺失说明视频已不在库中（例如刚下架），跳过这一条而不是报错。
                continue;
            }
            CachedVideoStat stat = stats.get(slot.videoId());
            items.add(new FeedItemVO(card.videoId(), card.authorId(), card.authorNickname(), card.authorAvatarUrl(),
                    card.title(), card.description(), card.mediaUrl(), card.coverUrl(),
                    stat == null ? 0 : stat.likeCount(),
                    stat == null ? 0 : stat.commentCount(),
                    stat == null ? 0 : stat.favoriteCount(),
                    liked.contains(card.videoId()), favorited.contains(card.videoId()),
                    followedAuthors.contains(card.authorId()),
                    // 卡片缓存里可能有发布时间（写入时带上），槽位里的作兜底。
                    card.publishedAt() != null ? card.publishedAt() : slot.publishedAt()));
        }
        return items;
    }

    /**
     * 计数三层合并：缓存命中直接用；缺失的查库并回填缓存；
     * 最后叠加尚未刷进数据库的点赞、收藏增量。
     */
    private Map<Long, CachedVideoStat> stats(List<Long> videoIds) {
        Map<Long, CachedVideoStat> cached = new HashMap<>(feedCache.findStats(videoIds));
        List<Long> missing = videoIds.stream().filter(id -> !cached.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Map<Long, VideoStat> stored = feedRepository.findStats(missing);
            Map<Long, CachedVideoStat> fresh = new HashMap<>();
            for (Long videoId : missing) {
                VideoStat stat = stored.get(videoId);
                // 统计记录缺失（如刚发布还没来得及写 video_stat）按零处理。
                CachedVideoStat value = new CachedVideoStat(
                        stat == null ? 0 : valueOrZero(stat.getLikeCount()),
                        stat == null ? 0 : valueOrZero(stat.getCommentCount()),
                        stat == null ? 0 : valueOrZero(stat.getFavoriteCount()));
                fresh.put(videoId, value);
                cached.put(videoId, value);
            }
            feedCache.saveStats(fresh);
        }
        Map<Long, CachedVideoStat> merged = new HashMap<>();
        for (Long videoId : videoIds) {
            CachedVideoStat stat = cached.getOrDefault(videoId, new CachedVideoStat(0, 0, 0));
            Map<VideoStatField, Long> pending = pending(videoId);
            // 评论数不叠加增量：评论直接写数据库，并在提交后删除计数缓存，
            // 所以已落库值就是最新值；点赞、收藏走增量缓冲，需要叠加。
            merged.put(videoId, new CachedVideoStat(
                    count(stat.likeCount(), pending.getOrDefault(VideoStatField.LIKE_COUNT, 0L)),
                    stat.commentCount(),
                    count(stat.favoriteCount(), pending.getOrDefault(VideoStatField.FAVORITE_COUNT, 0L))));
        }
        return merged;
    }

    /**
     * 读取增量缓冲；Redis 失败只打日志退化为已落库计数，不让 Feed 页失败。
     */
    private Map<VideoStatField, Long> pending(Long videoId) {
        try {
            return statBuffer.pending(videoId);
        } catch (RuntimeException ex) {
            log.warn("读取待刷库计数失败，Feed 只使用已落库计数 videoId={}: {}", videoId, ex.toString());
            return Map.of();
        }
    }

    /**
     * 视频列表转渲染槽位。
     */
    public List<PageSlot> slots(List<Video> videos) {
        return videos.stream().map(video -> new PageSlot(video.getId(), video.getAuthorId(), video.getPublishedAt())).toList();
    }

    /**
     * 视频 ID -> 视频索引，供 render 作为“本页已带出”的已知数据复用。
     */
    public Map<Long, Video> index(List<Video> videos) {
        Map<Long, Video> indexed = new HashMap<>();
        for (Video video : videos) {
            indexed.put(video.getId(), video);
        }
        return indexed;
    }

    /**
     * 已落库计数 + 待刷库增量，负数截断为 0，防止取关等反向操作后显示负值。
     */
    private int count(int stored, long pending) {
        return (int) Math.max(0, stored + pending);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
