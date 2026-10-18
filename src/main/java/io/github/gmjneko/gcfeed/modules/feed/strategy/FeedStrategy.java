package io.github.gmjneko.gcfeed.modules.feed.strategy;

import io.github.gmjneko.gcfeed.modules.feed.model.FeedScene;
import io.github.gmjneko.gcfeed.modules.feed.vo.FeedPageVO;

/**
 * Feed 场景策略：每种场景各自实现取页与装配，FeedServiceImpl 统一校验后按 scene 分发。
 * 新增场景只要新增一个实现类并注册为 Spring Bean，主流程零改动，见 docs/05 5.2。
 */
public interface FeedStrategy {

    /** 本策略负责的场景，同时作为注册表 Map 的键，两个实现返回同一场景会启动失败。 */
    FeedScene scene();

    /**
     * 是否必须登录。timeline/hot 匿名可看，following/recommend 必须有 viewerId。
     * 由 FeedServiceImpl 在分发前统一校验，实现类拿到的 viewerId 保证非空。
     */
    boolean requiresLogin();

    /**
     * 取一页并组装卡片。cursor 语义由各场景定义：推荐用快照偏移，热榜用窗口位置，
     * 时间线和关注流用时间/ID；limit 已被调用方收紧到 [1, 100]。
     */
    FeedPageVO list(FeedQuery query);
}
