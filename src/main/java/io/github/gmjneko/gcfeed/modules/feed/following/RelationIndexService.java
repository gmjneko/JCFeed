package io.github.gmjneko.gcfeed.modules.feed.following;

import io.github.gmjneko.gcfeed.modules.mq.RabbitSettings;
import io.github.gmjneko.gcfeed.modules.mq.RelationChangedEvent;
import io.github.gmjneko.gcfeed.modules.relation.repository.RelationRepository;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import io.github.gmjneko.gcfeed.modules.video.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 关注后回填最近作品，取关后从收件箱移除该作者。大 V 不回填。
 *
 * <p>消息里的 action 只是意图，处理时以数据库里关系的当前状态为准：消息可能延迟、
 * 重复或多实例并发消费时乱序，旧的关注事件晚于取关到达时，这里能看到关系已不在，
 * 直接跳过，不会把已取消的关注写进索引。
 */
@Service
public class RelationIndexService {

    private final RelationRepository relations;
    private final VideoRepository videos;
    private final FollowingIndex index;
    private final RabbitSettings settings;

    public RelationIndexService(RelationRepository relations, VideoRepository videos, FollowingIndex index,
                                RabbitSettings settings) {
        this.relations = relations;
        this.videos = videos;
        this.index = index;
        this.settings = settings;
    }

    public void onChanged(RelationChangedEvent event) {
        if (event == null || event.userId() <= 0 || event.targetUserId() <= 0) {
            return;
        }
        // 以库里当前关系为准：消息乱序或重复时旧动作不该生效。
        boolean active = relations.following(event.userId(), event.targetUserId());
        if (event.follow()) {
            // 关系已不存在，或对方是大 V（读取时拉发件箱）：都不需要回填收件箱。
            if (!active || relations.followerCount(event.targetUserId()) >= settings.bigVFollowerThreshold()) {
                return;
            }
            // 回填最近 backfillLimit 条已发布视频，新关注立刻能看到历史作品。
            for (Video video : videos.findPublicByAuthor(event.targetUserId(), settings.backfillLimit(), null)) {
                if (video.getPublishedAt() == null) {
                    continue;
                }
                index.addInbox(event.targetUserId(), List.of(event.userId()),
                        new FollowingItem(video.getId(), event.targetUserId(), video.getPublishedAt()));
            }
            return;
        }
        // 只有关系确实已断开才清理；active 为 true 说明是乱序的旧取关事件，索引保持不动。
        if (!active) {
            index.removeAuthorFromInbox(event.userId(), event.targetUserId());
        }
    }
}
