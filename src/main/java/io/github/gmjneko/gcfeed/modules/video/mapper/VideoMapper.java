package io.github.gmjneko.gcfeed.modules.video.mapper;

import io.github.gmjneko.gcfeed.modules.feed.model.FeedCursor;
import io.github.gmjneko.gcfeed.modules.video.entity.Video;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VideoMapper extends BaseMapper<Video> {

    /**
     * 按游标查询当前用户关注流的一页。
     *
     * <p>手写理由：相关子查询 {@code EXISTS}（见规范 §4.3 判据二）。改用「先取关注列表再拼
     * {@code IN}」不是等价改写——关注数很大时会把整份 ID 列表塞进 SQL，且每翻一页都要先全量
     * 拿到 IDs 才能筛选，无法像 {@code EXISTS} 这样走 {@code idx_status_published} 取 limit 条就停。
     */
    @Select("""
            <script>
            SELECT v.id, v.author_id, v.title, v.description, v.media_url, v.cover_url, v.published_at
            FROM video v
            WHERE v.status = 2 AND v.published_at IS NOT NULL
              AND EXISTS (SELECT 1 FROM user_follow f WHERE f.user_id = #{viewerId}
                          AND f.target_user_id = v.author_id AND f.status = 1)
            <if test="cursor != null">
              AND (v.published_at &lt; #{cursor.publishedAt}
                   OR (v.published_at = #{cursor.publishedAt} AND v.id &lt; #{cursor.videoId}))
            </if>
            ORDER BY v.published_at DESC, v.id DESC LIMIT #{fetchLimit}
            </script>
            """)
    java.util.List<Video> followingPage(@Param("viewerId") Long viewerId,
                                        @Param("cursor") FeedCursor cursor,
                                        @Param("fetchLimit") int fetchLimit);

}
