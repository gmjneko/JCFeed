package io.github.gmjneko.gcfeed.modules.feed.following;

import io.github.gmjneko.gcfeed.modules.embedding.service.VideoPublishedEvent;
import io.github.gmjneko.gcfeed.modules.feed.cache.FeedCache;
import io.github.gmjneko.gcfeed.modules.feed.cache.FeedCardCache;
import io.github.gmjneko.gcfeed.modules.feed.cache.CachedVideoStat;
import io.github.gmjneko.gcfeed.modules.feed.repository.FeedRepository;
import io.github.gmjneko.gcfeed.modules.mq.RabbitSettings;
import io.github.gmjneko.gcfeed.modules.relation.repository.RelationRepository;
import io.github.gmjneko.gcfeed.modules.user.entity.User;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import io.github.gmjneko.gcfeed.modules.video.entity.VideoStat;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 发布成功后的关注流扇出。普通作者推收件箱，大 V 只维护自己的发件箱。
 *
 * <p>推拉边界取 RabbitSettings.bigVFollowerThreshold：粉丝数低于阈值时逐批把视频写进
 * 每个粉丝的收件箱（推模式）；达到阈值只写作者发件箱，读取时由 RedisFollowingIndex
 * 现场合并进粉丝视图（拉模式），避免大 V 一次发布触发数十万次收件箱写入。
 */
@Service
public class VideoFanoutService {

    private final RelationRepository relations;
    private final FollowingIndex index;
    private final FeedRepository feed;
    private final FeedCache cache;
    private final RabbitSettings settings;

    public VideoFanoutService(RelationRepository relations, FollowingIndex index, FeedRepository feed,
                              FeedCache cache, RabbitSettings settings) {
        this.relations = relations;
        this.index = index;
        this.feed = feed;
        this.cache = cache;
        this.settings = settings;
    }

    public void onPublished(VideoPublishedEvent event) {
        // 防御性校验：视频 ID/作者 ID 缺失或非法时没有可扇出的内容，直接丢弃。
        if (event == null || event.videoId() == null || event.videoId() <= 0
                || event.authorId() == null || event.authorId() <= 0) {
            return;
        }
        // 以库里的当前状态为准：确认视频确实已发布，并取权威的发布时间。
        List<Video> videos = feed.findPublishedByIds(List.of(event.videoId()));
        if (videos.isEmpty() || videos.getFirst().getPublishedAt() == null) {
            return;
        }
        Video video = videos.getFirst();
        // 扇出前先预热卡片和计数缓存：关注流读取马上要用，避免粉丝首读全部回源。
        preheat(video);
        FollowingItem item = new FollowingItem(video.getId(), video.getAuthorId(), video.getPublishedAt());
        if (relations.followerCount(video.getAuthorId()) >= settings.bigVFollowerThreshold()) {
            // 大 V 走拉模式：只写作者发件箱，粉丝收件箱不动，读取时再合并。
            index.addOutbox(video.getAuthorId(), item);
            return;
        }
        long cursor = 0;
        while (true) {
            // 按 followerId 游标分批拉粉丝：每批一批收件箱写入，不一次性加载全量粉丝 ID。
            List<Long> followers = relations.followerIdsAfter(video.getAuthorId(), cursor, settings.followerBatchSize());
            if (followers.isEmpty()) {
                return;
            }
            index.addInbox(video.getAuthorId(), followers, item);
            cursor = followers.getLast();
            // 最后一批不满说明粉丝已拉完，扇出结束。
            if (followers.size() < settings.followerBatchSize()) {
                return;
            }
        }
    }

    /** 预热该视频的卡片缓存和计数缓存：关注流读取路径命中缓存就不用回源查库。 */
    private void preheat(Video video) {
        Map<Long, User> authors = feed.findAuthors(List.of(video.getAuthorId()));
        User author = authors.get(video.getAuthorId());
        cache.saveCards(List.of(new FeedCardCache(video.getId(), video.getAuthorId(),
                author == null ? null : author.getNickname(),
                author == null ? null : author.getAvatarUrl(),
                video.getTitle(), video.getDescription(), video.getMediaUrl(), video.getCoverUrl(),
                video.getPublishedAt())));
        Map<Long, VideoStat> stats = feed.findStats(List.of(video.getId()));
        VideoStat stat = stats.get(video.getId());
        cache.saveStats(Map.of(video.getId(), new CachedVideoStat(
                stat == null || stat.getLikeCount() == null ? 0 : stat.getLikeCount(),
                stat == null || stat.getCommentCount() == null ? 0 : stat.getCommentCount(),
                stat == null || stat.getFavoriteCount() == null ? 0 : stat.getFavoriteCount())));
    }
}
