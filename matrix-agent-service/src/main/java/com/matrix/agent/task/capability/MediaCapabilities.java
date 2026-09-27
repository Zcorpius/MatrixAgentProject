package com.matrix.agent.task.capability;

import com.matrix.agent.contract.schema.BilibiliVideoSchema;
import com.matrix.agent.contract.schema.CanonicalSchema;

import java.util.Set;

/** Closed, auditable media capability catalog. */
public final class MediaCapabilities {
    public static final String QQ_STATE = "media.qqmusic.get_state";
    public static final String QQ_OPEN = "media.qqmusic.open_app";
    public static final String QQ_PLAY = "media.qqmusic.play";
    public static final String QQ_PAUSE = "media.qqmusic.pause";
    public static final String QQ_NEXT = "media.qqmusic.next";
    public static final String QQ_PREVIOUS = "media.qqmusic.previous";
    public static final String QQ_SEEK = "media.qqmusic.seek";
    public static final String QQ_SEARCH = "media.qqmusic.search_songs";
    public static final String QQ_PLAY_RESULT = "media.qqmusic.play_search_result";
    public static final String BILI_STATE = "media.bilibili.get_state";
    public static final String BILI_OPEN = "media.bilibili.open_video";
    public static final String BILI_SEARCH = "media.bilibili.search_videos";
    public static final String BILI_OPEN_RESULT = "media.bilibili.open_search_result";
    public static final String BILI_PAUSE = "media.bilibili.pause";
    public static final String BILI_RESUME = "media.bilibili.resume";

    public static final Set<String> ALL = Set.of(QQ_STATE, QQ_OPEN, QQ_PLAY, QQ_PAUSE,
            QQ_NEXT, QQ_PREVIOUS, QQ_SEEK, QQ_SEARCH, QQ_PLAY_RESULT,
            BILI_STATE, BILI_OPEN, BILI_SEARCH, BILI_OPEN_RESULT, BILI_PAUSE, BILI_RESUME);
    private static final CanonicalSchema NO_ARGUMENTS =
            CanonicalSchema.object().additionalProperties(false).build();

    private MediaCapabilities() {}

    public static CapabilityRegistry registerInto(CapabilityRegistry registry) {
        registry.register(read(QQ_STATE, "查询 QQ 音乐当前播放状态与支持动作"));
        registry.register(write(QQ_OPEN, "打开 QQ 音乐首页；不自动播放", 8_000,
                VerifyMethod.NONE, true, NO_ARGUMENTS));
        registry.register(write(QQ_PLAY, "继续播放 QQ 音乐的当前队列；不能按歌手或歌名选曲", 6_000,
                VerifyMethod.READBACK_FIELD, true, NO_ARGUMENTS));
        registry.register(write(QQ_PAUSE, "暂停 QQ 音乐的唯一活动会话", 4_000,
                VerifyMethod.READBACK_FIELD, true, NO_ARGUMENTS));
        registry.register(write(QQ_NEXT, "播放 QQ 音乐下一首；不可自动重试", 2_500,
                VerifyMethod.NONE, false, NO_ARGUMENTS));
        registry.register(write(QQ_PREVIOUS, "播放 QQ 音乐上一首；不可自动重试", 2_500,
                VerifyMethod.NONE, false, NO_ARGUMENTS));
        registry.register(write(QQ_SEEK, "跳转 QQ 音乐播放位置，position_ms 为毫秒", 4_000,
                VerifyMethod.READBACK_FIELD, true,
                CanonicalSchema.object()
                        .property("position_ms", CanonicalSchema.integer().minimum(0).build())
                        .required("position_ms").additionalProperties(false).build()));
        registry.register(write(QQ_SEARCH, "在 QQ 音乐中搜索歌曲，参数 query 为歌名和/或歌手；返回候选及唯一精确匹配时的 media.confirmable_index，不播放。需用户启用 QQ 音乐搜索与选曲辅助功能", 12_000,
                VerifyMethod.NONE, true,
                CanonicalSchema.object()
                        .property("query", CanonicalSchema.string()
                                .minLength(1).maxLength(64).build())
                        .required("query").additionalProperties(false).build()));
        registry.register(write(QQ_PLAY_RESULT, "仅在用户新一轮明确指定歌曲/序号，或对唯一匹配项回复肯定确认后播放；参数 index 为先前搜索结果编号", 15_000,
                VerifyMethod.READBACK_FIELD, false,
                CanonicalSchema.object()
                        .property("index", CanonicalSchema.integer()
                                .minimum(1).maximum(8).build())
                        .required("index").additionalProperties(false).build()));
        registry.register(read(BILI_STATE, "查询哔哩哔哩当前播放状态与支持动作"));
        registry.register(write(BILI_OPEN, "打开用户指定 BV 号的视频入口；不保证开始播放", 8_000,
                VerifyMethod.NONE, true, BilibiliVideoSchema.arguments()));
        registry.register(write(BILI_SEARCH, "在哔哩哔哩搜索标题，返回候选结果，不打开视频", 15_000,
                VerifyMethod.NONE, true, CanonicalSchema.object()
                        .property("query", CanonicalSchema.string().minLength(1)
                                .maxLength(64).build())
                        .required("query").additionalProperties(false).build()));
        registry.register(write(BILI_OPEN_RESULT, "仅在用户新一轮明确指定先前搜索结果序号后打开该条目；不保证开始播放", 15_000,
                VerifyMethod.NONE, false, CanonicalSchema.object()
                        .property("index", CanonicalSchema.integer().minimum(1)
                                .maximum(8).build())
                        .required("index").additionalProperties(false).build()));
        registry.register(write(BILI_PAUSE, "暂停哔哩哔哩的唯一活动会话", 4_000,
                VerifyMethod.READBACK_FIELD, true, NO_ARGUMENTS));
        registry.register(write(BILI_RESUME, "恢复哔哩哔哩的唯一活动会话；车机需已停车", 6_000,
                VerifyMethod.READBACK_FIELD, true, NO_ARGUMENTS));
        return registry;
    }

    private static CapabilityDefinition read(String name, String description) {
        return base(name, RiskLevel.R0_READ_ONLY, description, 1_500,
                VerifyMethod.NONE, true, NO_ARGUMENTS).build();
    }

    private static CapabilityDefinition write(String name, String description, long timeoutMillis,
            VerifyMethod method, boolean idempotent, CanonicalSchema schema) {
        return base(name, RiskLevel.R1_LOW_RISK_WRITE, description, timeoutMillis,
                method, idempotent, schema).writeOperation(true).build();
    }

    private static CapabilityDefinition.Builder base(String name, RiskLevel risk,
            String description, long timeoutMillis, VerifyMethod method, boolean idempotent,
            CanonicalSchema schema) {
        return CapabilityDefinition.builder(name, risk)
                .description(description)
                .timeoutMillis(timeoutMillis)
                .maxRetries(0)
                .idempotent(idempotent)
                .verifyMethod(method)
                .parameterSchema(schema)
                .auditMessageTemplate("媒体操作已处理")
                .auditFailureMessageTemplate("媒体操作未确认，详情见 media.error_code")
                .sensitiveObservedField("media.title", "<media>")
                .sensitiveObservedField("media.media_id", "<media>")
                .sensitiveObservedField("media.artist", "<media>")
                .sensitiveObservedField("media.candidates", "<media-candidates>")
                .sensitiveObservedField("media.query", "<media-query>")
                .auditObservedAllowlist("media.app", "media.installed",
                        "media.session_state", "media.playback_state",
                        "media.supported_actions", "media.position_ms",
                        "media.duration_ms", "media.dispatch_state", "media.error_code");
    }
}
