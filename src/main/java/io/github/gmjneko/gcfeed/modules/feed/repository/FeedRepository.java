package io.github.gmjneko.gcfeed.modules.feed.repository;

import io.github.gmjneko.gcfeed.modules.feed.model.FeedCursor;
import io.github.gmjneko.gcfeed.modules.interaction.entity.InteractionAction;
import io.github.gmjneko.gcfeed.modules.user.entity.User;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import io.github.gmjneko.gcfeed.modules.video.entity.VideoStat;

import java.util.List;
import java.util.Map;

/** Feed 持久化边界：查询均为批量，不在 Service 中直接使用 Mapper。 */
public interface FeedRepository {
    /** 按时间游标读取上线视频，最多 limit + 1 条，额外一条供 Service 判断 has_more。 */
    List<Video> findTimeline(FeedCursor cursor, int limit);

    /** 批量读取上线且有发布时间的视频，保留输入 ID 顺序，供推荐页装配使用。 */
    List<Video> findPublishedByIds(List<Long> videoIds);

    /** 按时间游标读取当前用户已关注作者的上线视频，多取一条判断是否有下一页。 */
    List<Video> findFollowing(Long viewerId, FeedCursor cursor, int limit);

    /** 筛出当前用户已关注的作者 ID；匿名用户或空列表直接返回空集合。 */
    java.util.Set<Long> findFollowedAuthors(Long viewerId, List<Long> authorIds);

    /** 批量读取作者的公开展示资料并按作者 ID 返回，排除已注销账号。 */
    Map<Long, User> findAuthors(List<Long> authorIds);

    /** 批量读取视频互动统计并按视频 ID 返回，缺失记录由 Service 补零。 */
    Map<Long, VideoStat> findStats(List<Long> videoIds);

    /** 批量读取当前用户对本页视频的有效点赞、收藏行为；匿名用户或空页不查库。 */
    List<InteractionAction> findViewerActions(Long viewerId, List<Long> videoIds);
}
