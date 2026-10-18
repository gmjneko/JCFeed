package io.github.gmjneko.gcfeed.common.api;

/**
 * 全局业务错误码。
 *
 * <p>{@code code} 采用「模块(2 位) + 序号(3 位)」的约定，便于日志检索和问题定位。
 * 通用错误使用 {@code 00} 作为模块前缀，成功码固定为 {@code 0}。
 */
public enum ErrorCode {

    // ==================== 通用错误 00xxx ====================
    SUCCESS(0, "ok", 200),
    PARAM_ERROR(40000, "invalid request", 400),
    UNAUTHORIZED(40100, "unauthorized", 401),
    FORBIDDEN(40300, "forbidden", 403),
    NOT_FOUND(40400, "resource not found", 404),
    METHOD_NOT_ALLOWED(40500, "method not allowed", 405),
    CONFLICT(40900, "resource conflict", 409),
    TOO_MANY_REQUESTS(42900, "too many requests", 429),
    INTERNAL_ERROR(50000, "internal server error", 500),

    // ==================== 账号模块 10xxx ====================
    EMPTY_ACCOUNT(10001, "account is required"),
    EMPTY_PASSWORD(10002, "password is required"),
    EMPTY_NICKNAME(10003, "nickname is required"),
    ACCOUNT_TOO_LONG(10004, "account is too long"),
    PASSWORD_TOO_LONG(10005, "password is too long"),
    NICKNAME_TOO_LONG(10006, "nickname is too long"),
    BIO_TOO_LONG(10007, "bio is too long"),
    INVALID_USER_ID(10008, "user id must be positive"),
    EMPTY_PROFILE_UPDATE(10009, "profile update is required"),
    USER_NOT_FOUND(10010, "user not found", 404),
    ACCOUNT_ALREADY_EXISTS(10011, "account already exists", 409),
    INVALID_CREDENTIALS(10012, "invalid credentials", 401),
    USER_STATUS_ABNORMAL(10013, "user status is abnormal", 403),
    AVATAR_URL_TOO_LONG(10014, "avatar url is too long"),

    // ==================== 视频模块 20xxx ====================
    INVALID_VIDEO_ID(20001, "video id must be positive"),
    INVALID_AUTHOR_ID(20002, "author id must be positive"),
    EMPTY_VIDEO_TITLE(20003, "title is required"),
    VIDEO_TITLE_TOO_LONG(20004, "title is too long"),
    VIDEO_DESCRIPTION_TOO_LONG(20005, "description is too long"),
    EMPTY_MEDIA_URL(20006, "media url is required"),
    EMPTY_COVER_URL(20007, "cover url is required"),
    VIDEO_NOT_FOUND(20008, "video not found", 404),
    VIDEO_PERMISSION_DENIED(20009, "video permission denied", 403),
    PUBLISH_REQUEST_CONFLICT(20010, "publish request key was used with different video data", 409),
    MEDIA_URL_TOO_LONG(20011, "media url is too long"),
    COVER_URL_TOO_LONG(20012, "cover url is too long"),

    // ==================== 互动模块 30xxx ====================
    INVALID_COMMENT_ID(30001, "comment id must be positive"),
    INVALID_ACTION_TYPE(30002, "action type is invalid"),
    EMPTY_COMMENT_CONTENT(30003, "comment content is required"),
    COMMENT_CONTENT_TOO_LONG(30004, "comment content is too long"),
    COMMENT_NOT_FOUND(30005, "comment not found", 404),
    COMMENT_PERMISSION_DENIED(30006, "comment permission denied", 403),
    COMMENT_REQUEST_CONFLICT(30007, "comment request key conflicts with an existing comment", 409),

    // ==================== 关系模块 40xxx ====================
    INVALID_TARGET_USER_ID(40001, "target user id must be positive"),
    FOLLOW_SELF_FORBIDDEN(40002, "follow self forbidden"),
    TARGET_USER_NOT_FOUND(40003, "target user not found", 404),
    FOLLOW_NOT_FOUND(40004, "follow not found"),

    // ==================== 消息模块 50xxx ====================
    INVALID_MESSAGE_ID(50001, "message id must be positive"),
    INVALID_MESSAGE_TYPE(50002, "message type is invalid"),
    EMPTY_MESSAGE_TITLE(50003, "title is required"),
    MESSAGE_TITLE_TOO_LONG(50004, "title is too long"),
    EMPTY_MESSAGE_CONTENT(50005, "content is required"),
    MESSAGE_CONTENT_TOO_LONG(50006, "content is too long"),
    MESSAGE_NOT_FOUND(50007, "message not found"),
    EVENT_ID_TOO_LONG(50008, "event id is too long"),
    MESSAGE_IDEMPOTENCY_KEY_TOO_LONG(50009, "idempotency key is too long"),
    ACTOR_NICKNAME_TOO_LONG(50010, "actor nickname is too long"),
    ACTOR_AVATAR_URL_TOO_LONG(50011, "actor avatar url is too long"),
    LOAD_MESSAGE_FAILED(50012, "failed to load message", 500),
    SAVE_MESSAGE_FAILED(50013, "failed to save message", 500),
    UPDATE_MESSAGE_FAILED(50014, "failed to update message", 500),

    // ==================== 播放优化模块 60xxx ====================
    INVALID_PLATFORM(60001, "platform is invalid"),
    INVALID_NETWORK_TYPE(60002, "network type is invalid"),
    INVALID_FIRST_FRAME_MS(60003, "first frame ms must be non-negative"),
    INVALID_STUTTER_COUNT(60004, "stutter count must be non-negative"),
    INVALID_WATCH_MS(60005, "watch ms must be non-negative"),
    PLAYBACK_IDEMPOTENCY_KEY_TOO_LONG(60006, "idempotency key is too long"),
    LOAD_PLAYBACK_FAILED(60007, "failed to load playback data", 500),
    SAVE_QOS_FAILED(60008, "failed to save playback qos report", 500),

    // ==================== Feed / 推荐 / 曝光模块 70xxx ====================
    INVALID_LIMIT(70001, "limit must be positive"),
    INVALID_CURSOR(70002, "cursor is invalid"),
    UNSUPPORTED_SCENE(70003, "feed scene is unsupported"),
    VIEWER_REQUIRED(70004, "viewer is required", 401),
    INVALID_OFFSET(70005, "offset must be non-negative"),
    EMPTY_SCENE(70006, "scene is required"),
    SCENE_TOO_LONG(70007, "scene is too long"),
    INVALID_EVENT_TYPE(70008, "event type is invalid"),
    REQUEST_ID_TOO_LONG(70009, "request id is too long"),
    LOAD_FEED_FAILED(70010, "failed to load feed"),
    LOAD_RECOMMENDATION_FAILED(70011, "failed to load recommendations", 500),
    SAVE_VIEW_EVENT_FAILED(70012, "failed to save view event", 500),
    RECOMMENDATION_SESSION_EXPIRED(70013, "recommendation session expired", 410),
    HOT_RANKING_UNAVAILABLE(70014, "hot ranking is temporarily unavailable", 503),

    // ==================== 向量模块 71xxx ====================
    INVALID_EMBEDDING_VIDEO_ID(71001, "embedding video id must be positive"),
    EMBEDDING_DIMENSION_MISMATCH(71002, "embedding dimension mismatch"),
    EMBEDDING_SERIALIZE_FAILED(71003, "failed to serialize embedding", 500),
    SAVE_EMBEDDING_FAILED(71004, "failed to save embedding", 500),

    // ==================== 幂等 / 限流 80xxx ====================
    REQUEST_KEY_TOO_LONG(80001, "request key is too long"),
    DUPLICATE_SUBMISSION(80002, "duplicate submission within the idempotency window", 409),

    // ==================== 上传模块 90xxx ====================
    EMPTY_UPLOAD_FILE(90001, "file is required"),
    INVALID_UPLOAD_KIND(90002, "invalid upload kind"),
    UNSUPPORTED_UPLOAD_EXTENSION(90003, "unsupported upload extension"),
    UNSUPPORTED_UPLOAD_CONTENT_TYPE(90004, "unsupported upload content type"),
    UPLOAD_TOO_LARGE(90005, "upload file is too large"),
    VIDEO_DURATION_TOO_LONG(90006, "video duration is too long"),
    VIDEO_RESOLUTION_TOO_LARGE(90007, "video resolution is too large"),
    UNSUPPORTED_VIDEO_CODEC(90008, "video codec is unsupported"),
    INVALID_VIDEO_METADATA(90009, "video metadata is invalid"),
    VIDEO_TOOL_UNAVAILABLE(90010, "video tool is unavailable", 500),
    SAVE_UPLOAD_FAILED(90011, "failed to save upload", 500),
    VIDEO_FASTSTART_FAILED(90012, "video faststart failed", 500);

    /** 业务错误码，成功为 0。 */
    private final int code;

    /** 对外暴露的错误信息，保持英文短句，前端可直接兜底展示。 */
    private final String message;

    /** 该错误默认映射的 HTTP 状态码。 */
    private final int httpStatus;

    ErrorCode(int code, String message) {
        this(code, message, 400);
    }

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

}
