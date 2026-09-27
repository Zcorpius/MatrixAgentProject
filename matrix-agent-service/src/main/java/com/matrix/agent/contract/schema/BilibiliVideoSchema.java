package com.matrix.agent.contract.schema;

/** Shared, bounded public-video arguments for schema and provider validation. */
public final class BilibiliVideoSchema {
    public static final String BVID_PATTERN = "^BV[1-9A-HJ-NP-Za-km-z]{10}$";
    public static final int BVID_LENGTH = 12;
    public static final int MAX_VIDEO_PAGE = 999;

    private BilibiliVideoSchema() {}

    public static CanonicalSchema arguments() {
        return CanonicalSchema.object()
                .property("bvid", CanonicalSchema.string()
                        .pattern(BVID_PATTERN)
                        .minLength(BVID_LENGTH).maxLength(BVID_LENGTH)
                        .sensitive(true).sensitivePlaceholder("<video>").build())
                .property("page", CanonicalSchema.integer()
                        .minimum(1).maximum(MAX_VIDEO_PAGE).build())
                .required("bvid")
                .additionalProperties(false)
                .build();
    }

    public static boolean valid(String bvid, Object page) {
        if (bvid == null || !bvid.matches(BVID_PATTERN)) return false;
        if (page == null) return true;
        if (!(page instanceof Number)) return false;
        double value = ((Number) page).doubleValue();
        return Double.isFinite(value) && value == Math.rint(value)
                && value >= 1 && value <= MAX_VIDEO_PAGE;
    }
}
