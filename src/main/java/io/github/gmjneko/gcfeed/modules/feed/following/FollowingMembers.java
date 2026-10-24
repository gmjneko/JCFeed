package io.github.gmjneko.gcfeed.modules.feed.following;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** 收件箱成员与分数。分数把发布时间放在高位，相同时间再按视频 ID 区分。 */
public final class FollowingMembers {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private FollowingMembers() {
    }

    /** 分数 = 秒级时间戳 × 1e6 + 视频 ID 取模 1e6：同一秒内发布的视频靠 ID 拉开分数，排序近似「时间倒序、同秒再按 ID」。 */
    public static double score(FollowingItem item) {
        long epoch = item.publishedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        return epoch * 1_000_000d + (item.videoId() % 1_000_000);
    }

    /** member 自带作者 ID：取关清理时按它识别归属，不用回库查这条视频是谁发的。 */
    public static String member(FollowingItem item) {
        String time = item.publishedAt().atZone(ZoneId.systemDefault()).format(TIMESTAMP);
        return item.videoId() + ":" + item.authorId() + ":" + time;
    }

    /** 解析 member 字符串；格式不对只返回空，让调用方跳过这一条，不让整个收件箱读取失败。 */
    public static Optional<FollowingItem> parse(String member) {
        if (member == null) {
            return Optional.empty();
        }
        int first = member.indexOf(':');
        int second = member.indexOf(':', first + 1);
        if (first <= 0 || second <= first) {
            return Optional.empty();
        }
        try {
            long videoId = Long.parseLong(member.substring(0, first));
            long authorId = Long.parseLong(member.substring(first + 1, second));
            LocalDateTime publishedAt = java.time.OffsetDateTime.parse(member.substring(second + 1), TIMESTAMP)
                    .toLocalDateTime();
            if (videoId <= 0 || authorId <= 0 || publishedAt == null) {
                return Optional.empty();
            }
            return Optional.of(new FollowingItem(videoId, authorId, publishedAt));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static boolean belongsToAuthor(String member, long authorId) {
        return parse(member).filter(item -> item.authorId() == authorId).isPresent();
    }
}
