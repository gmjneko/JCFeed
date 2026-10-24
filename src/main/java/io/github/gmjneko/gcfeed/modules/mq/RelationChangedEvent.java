package io.github.gmjneko.gcfeed.modules.mq;

import java.time.Instant;

/**
 * 关注或取关已经提交后的分发事件。通知仍由原来的同步路径发送。
 *
 * <p>action 只是意图：消费端（RelationIndexService）处理时会回库确认关系当前状态，
 * 乱序或重复的消息不会把已取消的关注写进索引。
 */
public record RelationChangedEvent(
        String eventId,
        String action,
        long userId,
        long targetUserId,
        Instant occurredAt
) {
    public static final String FOLLOW = "follow";
    public static final String UNFOLLOW = "unfollow";

    public boolean follow() {
        return FOLLOW.equals(action);
    }
}
