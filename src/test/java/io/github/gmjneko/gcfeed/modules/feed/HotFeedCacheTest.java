package io.github.gmjneko.gcfeed.modules.feed;

import io.github.gmjneko.gcfeed.common.jwt.JwtUtil;
import io.github.gmjneko.gcfeed.modules.feed.cache.MemoryFeedCache;
import io.github.gmjneko.gcfeed.modules.feed.hot.HotRanking;
import io.github.gmjneko.gcfeed.modules.feed.hot.HotWindows;
import io.github.gmjneko.gcfeed.modules.feed.hot.MemoryHotRanking;
import io.github.gmjneko.gcfeed.modules.feed.model.HotCursor;
import io.github.gmjneko.gcfeed.modules.interaction.service.CommentService;
import io.github.gmjneko.gcfeed.modules.interaction.service.InteractionService;
import io.github.gmjneko.gcfeed.modules.video.InMemoryVideoStatDeltaBuffer;
import io.github.gmjneko.gcfeed.modules.video.job.VideoStatFlushJob;
import io.github.gmjneko.gcfeed.modules.video.model.VideoStatField;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 热榜窗口、Redis 故障响应，以及时间线页缓存和计数缓存。Redis 由内存实现替代。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(HotFeedCacheTest.CacheConfiguration.class)
class HotFeedCacheTest {

    private static final Instant START = Instant.parse("2026-10-14T06:00:00Z");
    private static final LocalDateTime PUBLISHED = LocalDateTime.parse("2026-10-11T12:34:56");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private MemoryHotRanking ranking;
    @Autowired
    private MemoryFeedCache feedCache;
    @Autowired
    private MutableClock clock;
    @Autowired
    private InMemoryVideoStatDeltaBuffer buffer;
    @Autowired
    private InteractionService interactions;
    @Autowired
    private CommentService comments;
    @Autowired
    private VideoStatFlushJob flushJob;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SqlRecorder sqlRecorder;
    @MockitoBean
    private RedissonClient redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void reset() {
        for (String table : List.of("interaction_comment", "interaction_action", "user_follow", "video_stat", "video",
                "user_relation_stat", "account")) {
            jdbc.update("DELETE FROM " + table);
        }
        ranking.clear();
        feedCache.clear();
        buffer.clear();
        clock.set(START);
        sqlRecorder.statements.clear();
        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(anyString())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void recentScoresExcludeNonPositiveAndKeepTheWindowAnchored() throws Exception {
        video(1, 1, PUBLISHED);
        video(2, 1, PUBLISHED.plusMinutes(1));
        video(3, 1, PUBLISHED.plusMinutes(2));
        ranking.add(1, HotRanking.LIKE_WEIGHT);
        ranking.add(2, HotRanking.FAVORITE_WEIGHT);
        ranking.add(2, -HotRanking.FAVORITE_WEIGHT);
        ranking.add(3, HotRanking.COMMENT_WEIGHT);

        JsonNode first = page(get("/api/feed-items").param("scene", "hot").param("limit", "1"));
        assertThat(ids(first)).containsExactly(3L);
        assertThat(first.get("has_more").asBoolean()).isTrue();
        LocalDateTime window = windowEnd(first.get("next_cursor").asText());
        assertThat(window).isEqualTo(HotWindows.minute(START));

        clock.set(START.plusSeconds(61 * 60));
        ranking.add(1, 100);
        JsonNode second = hot(first.get("next_cursor").asText());
        assertThat(ids(second)).containsExactly(1L);
        assertThat(windowEnd(second.get("next_cursor").asText())).isEqualTo(window);
        assertThat(ids(hot(""))).containsExactly(1L);
        assertThat(ranking.score(3, HotWindows.minute(START))).isEqualTo(HotRanking.COMMENT_WEIGHT);
    }

    @Test
    void redisFailureReturnsUnavailableAndWindowCursorRemainsRedisOnly() throws Exception {
        ranking.setAvailable(false);
        mvc.perform(get("/api/feed-items").param("scene", "hot"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(70014));

        String windowCursor = new HotCursor(HotWindows.minute(START), 0).encode(json);
        mvc.perform(get("/api/feed-items").param("scene", "hot").param("cursor", windowCursor))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(70014));
    }

    @Test
    void removedVideosDropOutOfTheHotPageWithoutChangingHasMore() throws Exception {
        video(1, 1, PUBLISHED);
        video(2, 1, PUBLISHED.plusMinutes(1));
        ranking.add(1, HotRanking.LIKE_WEIGHT);
        ranking.add(2, HotRanking.LIKE_WEIGHT + 1);
        jdbc.update("UPDATE video SET status = 4 WHERE id = 2");
        JsonNode page = page(get("/api/feed-items").param("scene", "hot").param("limit", "1"));
        assertThat(ids(page)).isEmpty();
        assertThat(page.get("has_more").asBoolean()).isTrue();
        assertThat(ids(hot(page.get("next_cursor").asText()))).containsExactly(1L);
    }

    @Test
    void timelinePageCacheSkipsThePageQueryAndKeepsViewerState() throws Exception {
        video(1, 1, PUBLISHED);
        video(2, 2, PUBLISHED.plusMinutes(1));
        jdbc.update("INSERT INTO user_follow (user_id, target_user_id, status) VALUES (9, 2, 1)");
        jdbc.update("INSERT INTO interaction_action (user_id, video_id, action_type, status) VALUES (9, 2, 'LIKE', 1)");
        jdbc.update("INSERT INTO interaction_action (user_id, video_id, action_type, status) VALUES (10, 1, 'FAVORITE', 1)");

        request(get("/api/feed-items"));
        sqlRecorder.statements.clear();
        JsonNode anonymous = request(get("/api/feed-items"));
        assertThat(sqlRecorder.statements).isEmpty();
        assertThat(item(anonymous, 2).get("liked").asBoolean()).isFalse();
        assertThat(item(anonymous, 2).get("following").asBoolean()).isFalse();

        sqlRecorder.statements.clear();
        JsonNode firstViewer = request(authenticated(9));
        JsonNode secondViewer = request(authenticated(10));
        assertThat(sqlRecorder.statements).noneMatch(sql -> sql.contains("order by published_at"));
        assertThat(item(firstViewer, 2).get("liked").asBoolean()).isTrue();
        assertThat(item(firstViewer, 2).get("following").asBoolean()).isTrue();
        assertThat(item(firstViewer, 1).get("favorited").asBoolean()).isFalse();
        assertThat(item(secondViewer, 2).get("liked").asBoolean()).isFalse();
        assertThat(item(secondViewer, 1).get("favorited").asBoolean()).isTrue();
        assertThat(item(secondViewer, 2).get("following").asBoolean()).isFalse();
    }

    @Test
    void statCacheShowsPendingLikesUntilFlushAndCommentCountsAfterEvict() throws Exception {
        video(1, 1, PUBLISHED);
        stat(1, 5, 0, 0);
        assertThat(item(request(get("/api/feed-items")), 1).get("like_count").asInt()).isEqualTo(5);

        buffer.add(1L, VideoStatField.LIKE_COUNT, 2);
        sqlRecorder.statements.clear();
        assertThat(item(request(get("/api/feed-items")), 1).get("like_count").asInt()).isEqualTo(7);
        assertThat(sqlRecorder.statements).noneMatch(sql -> sql.contains("video_stat"));

        jdbc.update("UPDATE video_stat SET like_count = 7 WHERE video_id = 1");
        buffer.drain(1L);
        feedCache.evictStat(1L);
        assertThat(item(request(get("/api/feed-items")), 1).get("like_count").asInt()).isEqualTo(7);

        comments.create(1L, 1L, "hello", "comment-key");
        comments.create(1L, 1L, "hello", "comment-key");
        assertThat(item(request(get("/api/feed-items")), 1).get("comment_count").asInt()).isEqualTo(1);
        assertThat(ranking.score(1, HotWindows.minute(START))).isEqualTo(HotRanking.COMMENT_WEIGHT);
        long commentId = jdbc.queryForObject("SELECT id FROM interaction_comment WHERE video_id = 1", Long.class);
        comments.delete(1L, "user", commentId);
        assertThat(item(request(get("/api/feed-items")), 1).get("comment_count").asInt()).isZero();
        assertThat(ranking.score(1, HotWindows.minute(START))).isZero();
    }

    @Test
    void rolledBackLikeDoesNotEnterTheHotWindowAndRepeatLikeDoesNotDoubleScore() {
        video(1, 1, PUBLISHED);
        stat(1, 0, 0, 0);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            interactions.like(1L, 1L, null);
            status.setRollbackOnly();
        });
        assertThat(ranking.score(1, HotWindows.minute(START))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_action", Integer.class)).isZero();

        interactions.like(1L, 1L, null);
        interactions.like(1L, 1L, null);
        assertThat(ranking.score(1, HotWindows.minute(START))).isEqualTo(HotRanking.LIKE_WEIGHT);
        interactions.unlike(1L, 1L, null);
        assertThat(ranking.score(1, HotWindows.minute(START))).isZero();
        flushJob.flush();
    }

    private JsonNode hot(String cursor) throws Exception {
        return page(get("/api/feed-items").param("scene", "hot").param("cursor", cursor));
    }

    /** 解码并断言是窗口游标，返回锚定分钟；其他形态会在这里炸掉，测试意图不变。 */
    private LocalDateTime windowEnd(String cursor) {
        return HotCursor.decode(cursor, json).windowEnd();
    }

    private JsonNode request(MockHttpServletRequestBuilder req) throws Exception {
        return page(req);
    }

    private JsonNode page(MockHttpServletRequestBuilder req) throws Exception {
        String body = mvc.perform(req).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private MockHttpServletRequestBuilder authenticated(long userId) {
        return get("/api/feed-items").header("Authorization", "Bearer " + jwtUtil.signAccessToken(userId, "user"));
    }

    private void video(long id, long authorId, LocalDateTime publishedAt) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id = ?", Integer.class, authorId);
        if (existing == null || existing == 0) {
            jdbc.update("INSERT INTO account (id, account, password, nickname) VALUES (?, ?, 'unused', ?)",
                    authorId, "hot-user-" + authorId, "author-" + authorId);
        }
        jdbc.update("""
                INSERT INTO video (id, author_id, title, description, media_url, cover_url, published_at)
                VALUES (?, ?, ?, 'desc', '/video.mp4', '/cover.jpg', ?)
                """, id, authorId, "video-" + id, publishedAt);
    }

    private void stat(long videoId, int likes, int comments, int favorites) {
        jdbc.update("INSERT INTO video_stat (video_id, like_count, comment_count, favorite_count) VALUES (?, ?, ?, ?)",
                videoId, likes, comments, favorites);
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("video_id").asLong()));
        return ids;
    }

    private JsonNode item(JsonNode page, long id) {
        for (JsonNode card : page.get("items")) {
            if (card.get("video_id").asLong() == id) {
                return card;
            }
        }
        throw new AssertionError("missing video " + id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CacheConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        @Primary
        MemoryHotRanking memoryHotRanking(MutableClock clock) {
            return new MemoryHotRanking(clock);
        }

        @Bean
        @Primary
        MemoryFeedCache memoryFeedCache() {
            return new MemoryFeedCache();
        }

        @Bean
        @Primary
        InMemoryVideoStatDeltaBuffer inMemoryVideoStatDeltaBuffer() {
            return new InMemoryVideoStatDeltaBuffer();
        }

        @Bean
        SqlRecorder sqlRecorder() {
            return new SqlRecorder();
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class SqlRecorder implements Interceptor {
        final List<String> statements = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            statements.add(handler.getBoundSql().getSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            return invocation.proceed();
        }
    }
}
