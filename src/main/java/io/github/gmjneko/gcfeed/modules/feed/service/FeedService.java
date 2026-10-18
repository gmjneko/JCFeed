package io.github.gmjneko.gcfeed.modules.feed.service;

import io.github.gmjneko.gcfeed.modules.feed.vo.FeedPageVO;

/** 统一 GET/POST 的分页与卡片装配，按场景选择时间线、热榜、关注流或推荐快照。 */
public interface FeedService {
    /** 无客户端上下文的查询入口，使用相同场景和分页规则。 */
    default FeedPageVO getFeed(String scene, String cursor, int limit, Long viewerId) {
        return getFeed(scene, cursor, limit, viewerId, null);
    }

    /**
     * 查询 Feed。timeline 和 hot 允许匿名，following/recommend 必须有已验证的 viewerId。
     *
     * <p>推荐游标使用 Redis 快照，热榜使用 Redis 窗口游标，时间和关注流使用时间/ID 游标。
     * 非正数 limit、非法游标、未知场景返回参数错误，缺失必需身份返回 401，推荐快照失效返回 410。
     *
     * @param requestId 可空的链路标识，推荐查询透传，不作为幂等键或排序依据
     * @return 保留场景页顺序的卡片及游标信息
     */
    FeedPageVO getFeed(String scene, String cursor, int limit, Long viewerId, String requestId);

}
