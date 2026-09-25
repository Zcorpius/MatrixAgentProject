package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recalls only safe, relevant recent-task metadata. No raw trajectory is ever projected. */
public final class EpisodicMemorySourceImpl implements EpisodicMemorySource {
    private static final String TAG = "MatrixAgent";
    static final int DEFAULT_LIMIT = 5;
    private final SessionHistoryDao dao;
    private final int limit;

    public EpisodicMemorySourceImpl(SessionHistoryDao dao) {
        this(dao, DEFAULT_LIMIT);
    }

    public EpisodicMemorySourceImpl(SessionHistoryDao dao, int limit) {
        if (dao == null) throw new IllegalArgumentException("dao required");
        this.dao = dao;
        this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
    }

    @Override
    public List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems) {
        if (scope == null || maxItems <= 0 || !EpisodicQueryIntent.isHistoryQuestion(userText)) {
            return Collections.emptyList();
        }
        try {
            List<SessionHistoryEntity> rows = dao.queryByUserZone(scope.getUserId(),
                    scope.getZone().wireValue(), Math.max(limit * 6, 30));
            if (rows == null || rows.isEmpty()) return Collections.emptyList();
            String query = userText.toLowerCase(Locale.ROOT);
            boolean categoryRequested = matches("climate", query)
                    || matches("navigation", query) || matches("media", query)
                    || matches("seat", query) || matches("display", query);
            List<MemorySnippet> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (SessionHistoryEntity row : rows) {
                if (out.size() >= Math.min(maxItems, limit)) break;
                List<String> categories = categoriesOf(row.trajectoryJson);
                if (categories.isEmpty()) continue;
                String state = "SUCCEEDED".equals(row.finalState) ? "succeeded" :
                        "FAILED".equals(row.finalState) ? "failed" : null;
                if (state == null) continue;
                String eventId = eventIdOf(row.trajectoryJson);
                if (eventId != null && row.startedAtMillis
                        < System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000) continue;
                for (String category : categories) {
                    if (out.size() >= Math.min(maxItems, limit)) break;
                    if (categoryRequested && !matches(category, query)) continue;
                    String key = eventId == null ? "recent_task." + category + "." + state
                            : "recent." + category + "." + state + "." + eventId;
                    if (!seen.add(key)) continue;
                    out.add(new MemorySnippet(MemoryLayer.EPISODIC, scope, key, "",
                            1.0, row.startedAtMillis, row.sessionId));
                    if (!categoryRequested) break;
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Exception failure) {
            Log.w(TAG, "[EpisodicMemorySource] recall failed cause="
                    + failure.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static List<String> categoriesOf(String summary) {
        if (summary == null || summary.length() > 2048) return Collections.emptyList();
        try {
            JSONObject event = new JSONObject(summary);
            if (event.has("eventSchemaVersion")) {
                int version = event.optInt("eventSchemaVersion", -1);
                if (version != 1 && version != EpisodicEventKind.SCHEMA_VERSION) {
                    return Collections.emptyList();
                }
                if (version == EpisodicEventKind.SCHEMA_VERSION
                        && (!EpisodicFactCodec.validEventId(event.optString("eventId", null))
                        || !EpisodicFactCodec.validFactsForCapabilities(
                                event.optJSONArray("verifiedFacts"),
                                event.optJSONArray("successfulCapabilities")))) {
                    return Collections.emptyList();
                }
                return categoriesFromCapabilities(event.optJSONArray("successfulCapabilities"),
                        event.optString("eventKind", ""));
            }
            // Bounded compatibility for pre-protocol safe summaries. v14 migration removes
            // full historical trajectories, so only explicitly allowlisted capability names
            // can establish a category here.
            JSONArray capabilities = event.optJSONArray("successfulCapabilities");
            return categoriesFromCapabilities(capabilities, "");
        } catch (Exception invalidSummary) {
            return Collections.emptyList();
        }
    }

    private static List<String> categoriesFromCapabilities(JSONArray capabilities,
            String fallbackEventKind) {
        Set<String> kinds = new LinkedHashSet<>();
        if (capabilities != null) {
            for (int i = 0; i < Math.min(capabilities.length(), 3); i++) {
                EpisodicEventKind kind = EpisodicEventKind.fromCapability(
                        capabilities.optString(i, ""));
                if (kind != null) kinds.add(kind.wireValue());
            }
        }
        if (kinds.isEmpty()) {
            EpisodicEventKind fallback = EpisodicEventKind.fromWireValue(fallbackEventKind);
            if (fallback != null) kinds.add(fallback.wireValue());
        }
        return List.copyOf(kinds);
    }

    private static String eventIdOf(String summary) {
        if (summary == null || summary.length() > 2048) return null;
        try {
            JSONObject event = new JSONObject(summary);
            String id = event.optString("eventId", null);
            return event.optInt("eventSchemaVersion", -1) == EpisodicEventKind.SCHEMA_VERSION
                    && EpisodicFactCodec.validEventId(id) ? id : null;
        } catch (Exception invalid) { return null; }
    }

    private static boolean matches(String category, String query) {
        switch (category) {
            case "climate": return query.contains("空调") || query.contains("温度")
                    || query.contains("climate") || query.contains("temperature");
            case "navigation": return query.contains("导航") || query.contains("路线")
                    || query.contains("目的地") || query.contains("去哪")
                    || query.contains("navigation") || query.contains("route")
                    || query.contains("destination");
            case "media": return query.contains("音乐") || query.contains("播放")
                    || query.contains("音量") || query.contains("media") || query.contains("music")
                    || query.contains("volume");
            case "display": return query.contains("亮度") || query.contains("屏幕")
                    || query.contains("brightness") || query.contains("display");
            case "seat": return query.contains("座椅") || query.contains("座位")
                    || query.contains("seat");
            default: return false;
        }
    }

    /** Kept for writer compatibility. Queries are uncached so a clear is visible immediately. */
    public void invalidateCache() { }
}
