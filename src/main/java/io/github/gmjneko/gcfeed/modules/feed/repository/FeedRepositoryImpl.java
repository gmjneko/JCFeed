package io.github.gmjneko.gcfeed.modules.feed.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.gmjneko.gcfeed.common.constant.CommonConstant;
import io.github.gmjneko.gcfeed.modules.feed.model.FeedCursor;
import io.github.gmjneko.gcfeed.modules.interaction.entity.InteractionAction;
import io.github.gmjneko.gcfeed.modules.interaction.mapper.InteractionActionMapper;
import io.github.gmjneko.gcfeed.modules.user.entity.User;
import io.github.gmjneko.gcfeed.modules.user.mapper.UserMapper;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import io.github.gmjneko.gcfeed.modules.video.entity.VideoStat;
import io.github.gmjneko.gcfeed.modules.video.mapper.VideoMapper;
import io.github.gmjneko.gcfeed.modules.video.mapper.VideoStatMapper;
import org.springframework.stereotype.Repository;

import io.github.gmjneko.gcfeed.modules.relation.entity.UserFollow;
import io.github.gmjneko.gcfeed.modules.relation.mapper.UserFollowMapper;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 直接查询数据库的基础 Timeline，不使用 XML、count 或按页码翻页。 */
@Repository
public class FeedRepositoryImpl implements FeedRepository {
    private final UserFollowMapper follows;
    private final VideoMapper videoMapper;
    private final UserMapper userMapper;
    private final VideoStatMapper statMapper;
    private final InteractionActionMapper actionMapper;

    public FeedRepositoryImpl(VideoMapper videoMapper, UserMapper userMapper,
                              VideoStatMapper statMapper, InteractionActionMapper actionMapper, UserFollowMapper follows) {
        this.follows = follows;
        this.videoMapper = videoMapper;
        this.userMapper = userMapper;
        this.statMapper = statMapper;
        this.actionMapper = actionMapper;
    }

    @Override
    public List<Video> findPublishedByIds(List<Long> videoIds) {
        if (videoIds.isEmpty()) return List.of();
        Map<Long, Video> videos = videoMapper.selectList(Wrappers.<Video>lambdaQuery()
                .select(Video::getId, Video::getAuthorId, Video::getTitle, Video::getDescription,
                        Video::getMediaUrl, Video::getCoverUrl, Video::getPublishedAt)
                .in(Video::getId, videoIds).eq(Video::getStatus, CommonConstant.VIDEO_STATUS_PUBLISHED)
                .isNotNull(Video::getPublishedAt)).stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        // SQL IN 不保证顺序，必须按推荐页给出的 ID 装配，不能用数据库返回顺序重排卡片。
        return videoIds.stream().map(videos::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Video> findFollowing(Long viewerId, FeedCursor cursor, int limit) {
        return videoMapper.followingPage(viewerId, cursor, limit + 1);
    }

    @Override
    public Set<Long> findFollowedAuthors(Long viewerId, List<Long> authorIds) {
        if (viewerId == null || authorIds.isEmpty()) return Set.of();
        return follows.selectList(Wrappers.<UserFollow>lambdaQuery().select(UserFollow::getTargetUserId)
                .eq(UserFollow::getUserId, viewerId).eq(UserFollow::getStatus, 1)
                .in(UserFollow::getTargetUserId, authorIds)).stream()
                .map(UserFollow::getTargetUserId).collect(Collectors.toSet());
    }

    @Override
    public List<Video> findTimeline(FeedCursor cursor, int limit) {
        // 仅取卡片所需字段；没有发布时间的视频无法构造稳定游标，必须排除。
        var query = Wrappers.<Video>lambdaQuery()
                .select(Video::getId, Video::getAuthorId, Video::getTitle, Video::getDescription,
                        Video::getMediaUrl, Video::getCoverUrl, Video::getPublishedAt)
                .eq(Video::getStatus, CommonConstant.VIDEO_STATUS_PUBLISHED)
                .isNotNull(Video::getPublishedAt)
                .orderByDesc(Video::getPublishedAt, Video::getId);
        if (cursor != null) {
            // 严格小于双键位置，避免同一发布时间下重复或漏项；外层 AND 包住整个 OR，
            // 确保两种续页分支都受上线状态条件约束。
            query.and(condition -> condition
                    .lt(Video::getPublishedAt, cursor.publishedAt())
                    .or(equalTime -> equalTime
                            .eq(Video::getPublishedAt, cursor.publishedAt())
                            .lt(Video::getId, cursor.videoId())));
        }
        // 固定第 1 页仅用于生成 LIMIT；由游标推进位置，关闭 count 并多取一条判断 has_more。
        return videoMapper.selectPage(new Page<>(1, limit + 1, false), query).getRecords();
    }

    @Override
    public Map<Long, User> findAuthors(List<Long> authorIds) {
        // 空集合直接返回，避免生成空 IN 条件。
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        // 只读取公开展示字段，不把账号、密码等私有数据带入 Feed 组装链路；已注销账号不展示资料。
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getNickname, User::getAvatarUrl)
                        .in(User::getId, authorIds)
                        .ne(User::getStatus, CommonConstant.USER_STATUS_CANCELLED)).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    @Override
    public Map<Long, VideoStat> findStats(List<Long> videoIds) {
        // 空页无需访问统计表；不存在的统计记录由 Service 统一补零。
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        return statMapper.selectList(Wrappers.<VideoStat>lambdaQuery()
                        .select(VideoStat::getVideoId, VideoStat::getLikeCount,
                                VideoStat::getCommentCount, VideoStat::getFavoriteCount)
                        .in(VideoStat::getVideoId, videoIds)).stream()
                .collect(Collectors.toMap(VideoStat::getVideoId, Function.identity()));
    }

    @Override
    public List<InteractionAction> findViewerActions(Long viewerId, List<Long> videoIds) {
        // 匿名或空页不查互动表，也不能省略用户条件后查询所有人的行为。
        if (viewerId == null || videoIds.isEmpty()) {
            return List.of();
        }
        // 取消态不生效；查询限定为当前用户、本页视频和两种按钮状态。
        return actionMapper.selectList(Wrappers.<InteractionAction>lambdaQuery()
                .select(InteractionAction::getVideoId, InteractionAction::getActionType)
                .eq(InteractionAction::getUserId, viewerId)
                .in(InteractionAction::getVideoId, videoIds)
                .eq(InteractionAction::getStatus, InteractionAction.STATUS_ACTIVE)
                .in(InteractionAction::getActionType, InteractionAction.LIKE, InteractionAction.FAVORITE));
    }
}
