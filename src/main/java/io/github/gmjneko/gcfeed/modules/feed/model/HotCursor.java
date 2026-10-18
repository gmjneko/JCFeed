package io.github.gmjneko.gcfeed.modules.feed.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.gmjneko.gcfeed.common.api.ErrorCode;
import io.github.gmjneko.gcfeed.common.exception.BusinessException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Base64;

/** Redis 热榜窗口游标，记录锚定分钟和快照内偏移。 */
public record HotCursor(
        @JsonProperty("window_end") LocalDateTime windowEnd,
        int offset
) {

    public HotCursor {
        if (windowEnd == null || offset < 0) {
            throw new IllegalArgumentException("hot cursor requires a window and non-negative offset");
        }
        windowEnd = windowEnd.withSecond(0).withNano(0);
    }

    public String encode(JsonMapper json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(this));
    }

    /** 空字符串表示首页；其他游标必须是有效的窗口位置。 */
    public static HotCursor decode(String encoded, JsonMapper json) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded.strip());
            var node = json.readTree(bytes);
            if (!node.isObject() || !node.path("window_end").isString()
                    || !node.path("offset").isIntegralNumber()) {
                throw new IllegalArgumentException("hot window cursor is invalid");
            }
            return json.readValue(bytes, HotCursor.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR, ex);
        }
    }
}
