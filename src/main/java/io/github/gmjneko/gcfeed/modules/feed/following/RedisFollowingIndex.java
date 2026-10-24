package io.github.gmjneko.gcfeed.modules.feed.following;

import io.github.gmjneko.gcfeed.common.constant.RedisKeyConstant;
import io.github.gmjneko.gcfeed.modules.feed.model.FeedCursor;
import io.github.gmjneko.gcfeed.modules.mq.RabbitSettings;
import io.github.gmjneko.gcfeed.modules.relation.repository.RelationRepository;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 用 ZSET 保存关注流。任何 Redis 异常都交给调用方回退到数据库查询。
 *
 * <p>每个用户一个收件箱 ZSET，每个大 V 一个发件箱 ZSET；member 是
 * "videoId:authorId:发布时间" 字符串，score 把发布时间放高位、视频 ID 放低位（见 FollowingMembers）。
 * page 把收件箱与大 V 发件箱合并、去重、按时间倒序返回；索引没有数据或 Redis 异常时返回空
 * Optional，由 FollowingFeedStrategy 退回数据库路径。
 */
@Component
public class RedisFollowingIndex implements FollowingIndex {

    private static final Logger log = LoggerFactory.getLogger(RedisFollowingIndex.class);

    private final RedissonClient redis;
    private final RelationRepository relations;
    private final RabbitSettings settings;

    public RedisFollowingIndex(RedissonClient redis, RelationRepository relations, RabbitSettings settings) {
        this.redis = redis;
        this.relations = relations;
        this.settings = settings;
    }

    @Override
    public void addInbox(long authorId, List<Long> userIds, FollowingItem item) {
        if (userIds == null || item == null) {
            return;
        }
        // 扇出以批为单位调用：同一批粉丝逐个写各自的收件箱，非法 ID 只跳过不中断整批。
        for (Long userId : userIds) {
            if (userId != null && userId > 0) {
                add(inboxKey(userId), item, settings.inboxMaxLen());
            }
        }
    }

    /** 大 V 发件箱：读取时才由 page 合并进粉丝视图，发布时不触碰粉丝收件箱。 */
    @Override
    public void addOutbox(long authorId, FollowingItem item) {
        add(outboxKey(authorId), item, settings.outboxMaxLen());
    }

    /** 取关清理：全量扫收件箱，按 member 里的作者 ID 识别并删除该作者的视频。 */
    @Override
    public void removeAuthorFromInbox(long userId, long authorId) {
        // 收件箱最长 inboxMaxLen 条，全量扫描成本可控；member 自带作者 ID，无需逐条回库。
        RScoredSortedSet<String> inbox = set(inboxKey(userId));
        Collection<String> members = inbox.readAll();
        List<String> matched = members.stream().filter(member -> FollowingMembers.belongsToAuthor(member, authorId)).toList();
        if (!matched.isEmpty()) {
            inbox.removeAll(matched);
        }
    }

    @Override
    public Optional<List<FollowingItem>> page(long viewerId, FeedCursor cursor, int limit) {
        try {
            // 收件箱（推）与大 V 发件箱（拉）合并出完整视图；先探测两边是否有数据。
            RScoredSortedSet<String> inbox = set(inboxKey(viewerId));
            boolean inboxHasItems = inbox.size() > 0;
            // 大 V 列表每次读取现查：作者粉丝数变化后，拉取范围跟着变，无需维护反向索引。
            List<Long> bigVAuthors = relations.bigVAuthorIds(viewerId, settings.bigVFollowerThreshold());
            List<RScoredSortedSet<String>> outboxes = new ArrayList<>();
            boolean outboxHasItems = false;
            for (Long authorId : bigVAuthors) {
                RScoredSortedSet<String> outbox = set(outboxKey(authorId));
                if (outbox.size() > 0) {
                    outboxHasItems = true;
                    outboxes.add(outbox);
                }
            }
            // 两边都空说明该用户还没有任何索引数据（或刚过期）：返回空让调用方走数据库。
            if (!inboxHasItems && !outboxHasItems) {
                return Optional.empty();
            }
            List<FollowingItem> merged = new ArrayList<>();
            if (inboxHasItems) {
                merged.addAll(read(inbox));
            }
            for (RScoredSortedSet<String> outbox : outboxes) {
                merged.addAll(read(outbox));
            }
            List<FollowingItem> ordered = merged.stream()
                    .filter(item -> newer(item, cursor))
                    .distinct()
                    .sorted(Comparator.comparing(FollowingItem::publishedAt).reversed()
                            .thenComparing(Comparator.comparingLong(FollowingItem::videoId).reversed()))
                    .limit(Math.max(limit, 0L))
                    .toList();
            return Optional.of(ordered);
        } catch (RuntimeException ex) {
            log.warn("关注流索引不可用，改为查询数据库 viewerId={}: {}", viewerId, ex.toString());
            return Optional.empty();
        }
    }

    /** 写入并保持长度封顶：按排名删掉最旧的成员，再刷新过期时间。 */
    private void add(String key, FollowingItem item, int maxLen) {
        RScoredSortedSet<String> set = set(key);
        set.add(FollowingMembers.score(item), FollowingMembers.member(item));
        int size = set.size();
        // removeRangeByRank(0, ...) 删的是分数最低（最旧）的一段，收件箱只保留最近 maxLen 条。
        if (size > maxLen) {
            set.removeRangeByRank(0, size - maxLen - 1);
        }
        // 每次写入都续期：活跃用户的索引常驻，不活跃的到期由 Redis 清理。
        set.expire(Duration.ofSeconds(RedisKeyConstant.TTL_FEED_FOLLOWING_SECONDS));
    }

    /** 倒序读取前 inboxMaxLen 条：合并窗口有限，更早的作品由数据库路径兜底。 */
    private List<FollowingItem> read(RScoredSortedSet<String> set) {
        Collection<ScoredEntry<String>> entries = set.entryRangeReversed(0, settings.inboxMaxLen() - 1);
        List<FollowingItem> items = new ArrayList<>();
        for (ScoredEntry<String> entry : entries) {
            FollowingMembers.parse(entry.getValue()).ifPresent(items::add);
        }
        return items;
    }

    /** 翻页过滤：只保留严格排在游标之后（更旧）的条目；时间相同时按视频 ID 比较，与数据库游标语义一致。 */
    private boolean newer(FollowingItem item, FeedCursor cursor) {
        if (cursor == null) {
            return true;
        }
        int byTime = item.publishedAt().compareTo(cursor.publishedAt());
        return byTime < 0 || (byTime == 0 && item.videoId() < cursor.videoId());
    }

    private RScoredSortedSet<String> set(String key) {
        RScoredSortedSet<String> set = redis.getScoredSortedSet(key, StringCodec.INSTANCE);
        if (set == null) {
            throw new IllegalStateException("redis scored set is null");
        }
        return set;
    }

    private static String inboxKey(long userId) {
        return RedisKeyConstant.FEED_FOLLOWING_INBOX.formatted(userId);
    }

    private static String outboxKey(long authorId) {
        return RedisKeyConstant.FEED_FOLLOWING_AUTHOR.formatted(authorId);
    }
}
