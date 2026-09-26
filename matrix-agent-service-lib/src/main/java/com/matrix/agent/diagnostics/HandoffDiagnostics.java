package com.matrix.agent.diagnostics;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded process-local evidence. No payload text, disk I/O, timers or Android dependencies.
 * Counts cover this process lifetime; quantiles cover the last CAPACITY timed events per bucket,
 * including failures. Snapshot/export never holds the recording lock while sorting or writing.
 */
public final class HandoffDiagnostics {
    public enum Stage { HANDOFF, WINDOW_ATTACH, FIRST_DRAW, INTENT_DISPATCH, TASK_TERMINAL }
    public static final int CAPACITY = 256;
    public static final HandoffDiagnostics NONE = new HandoffDiagnostics(false);
    public record Event(Stage stage, int mode, int result, int reason, boolean success,
            long elapsedRealtimeMs, long durationMs, String task, String request) {}
    public record Summary(Stage stage, int mode, long count, long successes,
            Map<Integer, Long> results, int timedSamples, long p50Ms, long p95Ms, long maxMs) {}
    public record Snapshot(List<Event> events, List<Summary> summaries) {}
    private record Key(Stage stage, int mode) {}
    private record BucketCopy(Key key, long count, long successes,
            Map<Integer, Long> results, long[] durations) {}
    private static final class Bucket {
        long count, successes;
        final Map<Integer, Long> results = new LinkedHashMap<>();
        final ArrayDeque<Long> durations = new ArrayDeque<>();
    }
    private final boolean enabled;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Map<Key, Bucket> buckets = new LinkedHashMap<>();
    public HandoffDiagnostics() { this(true); }
    private HandoffDiagnostics(boolean enabled) { this.enabled = enabled; }

    public void record(Stage stage, int mode, int result, int reason, boolean success,
            long now, long durationMs, String taskId, String requestId) {
        if (!enabled) return;
        // Fixed cardinality: mode 0 means no presentation request, -1 means unknown.
        int boundedMode = mode >= 0 && mode <= 2 ? mode : -1;
        int boundedResult = result >= 0 && result <= 64 ? result : -1;
        Event event = new Event(java.util.Objects.requireNonNull(stage), boundedMode, boundedResult,
                reason >= 0 && reason <= 64 ? reason : -1, success, now,
                Math.max(-1, durationMs), hash(taskId), hash(requestId));
        synchronized (this) {
            if (events.size() == CAPACITY) events.removeFirst();
            events.addLast(event);
            Bucket bucket = buckets.computeIfAbsent(new Key(stage, boundedMode), ignored -> new Bucket());
            bucket.count++;
            if (success) bucket.successes++;
            bucket.results.merge(boundedResult, 1L, Long::sum);
            if (durationMs >= 0) {
                if (bucket.durations.size() == CAPACITY) bucket.durations.removeFirst();
                bucket.durations.addLast(durationMs);
            }
        }
    }

    public Snapshot snapshot() {
        List<Event> recent;
        List<BucketCopy> copies = new ArrayList<>();
        synchronized (this) {
            recent = List.copyOf(events);
            buckets.forEach((key, bucket) -> copies.add(new BucketCopy(key, bucket.count,
                    bucket.successes, Map.copyOf(bucket.results),
                    bucket.durations.stream().mapToLong(Long::longValue).toArray())));
        }
        List<Summary> summaries = new ArrayList<>();
        for (BucketCopy copy : copies) {
            long[] sorted = copy.durations();
            Arrays.sort(sorted);
            summaries.add(new Summary(copy.key().stage(), copy.key().mode(), copy.count(),
                    copy.successes(), copy.results(), sorted.length, percentile(sorted, .50),
                    percentile(sorted, .95), percentile(sorted, 1)));
        }
        return new Snapshot(recent, List.copyOf(summaries));
    }
    private static long percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? -1 : sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }
    private static String hash(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] output = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                output[i * 2] = hex[(digest[i] & 255) >>> 4]; output[i * 2 + 1] = hex[digest[i] & 15];
            }
            return new String(output);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** JSONL: schema header, independent summaries, then correlated recent events.
     * All strings are enum names or generated hexadecimal digests, never arbitrary input. */
    public void dump(PrintWriter out) {
        Snapshot copy = snapshot();
        out.println("{\"schema\":1,\"retention\":\"process_memory\",\"capacity\":" + CAPACITY
                + ",\"quantile\":\"nearest_rank_recent_timed_events_including_failures\"}");
        for (Summary row : copy.summaries()) {
            StringBuilder results = new StringBuilder("{");
            row.results().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (results.length() > 1) results.append(',');
                results.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            });
            results.append('}');
            out.println("{\"type\":\"summary\",\"stage\":\"" + row.stage() + "\",\"mode\":" + row.mode()
                    + ",\"count\":" + row.count() + ",\"successes\":" + row.successes()
                    + ",\"results\":" + results + ",\"timedSamples\":" + row.timedSamples()
                    + ",\"p50Ms\":" + row.p50Ms() + ",\"p95Ms\":" + row.p95Ms() + ",\"maxMs\":" + row.maxMs() + "}");
        }
        for (Event row : copy.events()) {
            out.println("{\"type\":\"event\",\"stage\":\"" + row.stage() + "\",\"mode\":" + row.mode()
                    + ",\"result\":" + row.result() + ",\"reason\":" + row.reason() + ",\"success\":" + row.success()
                    + ",\"elapsedRealtimeMs\":" + row.elapsedRealtimeMs() + ",\"durationMs\":" + row.durationMs()
                    + ",\"task\":\"" + row.task() + "\",\"request\":\"" + row.request() + "\"}");
        }
    }
}
