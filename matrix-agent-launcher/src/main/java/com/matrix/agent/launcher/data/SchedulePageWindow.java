package com.matrix.agent.launcher.data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/** Rebuilds the visible window on refresh, removing deleted rows without discarding loaded pages. */
public final class SchedulePageWindow {
    private SchedulePageWindow() { }
    public record Page<T>(List<T> items, String nextCursor, long sequence) { }

    public static <T> Page<T> read(int minimumItems, Function<String, Page<T>> source,
            Function<T, String> identity) {
        if (minimumItems < 1) throw new IllegalArgumentException("positive window required");
        var values = new LinkedHashMap<String, T>();
        var visited = new HashSet<String>();
        String cursor = "";
        long sequence = Long.MAX_VALUE;
        // Also bounds a faulty source returning fresh cursors without any rows.
        for (int pages = 0; pages < minimumItems; pages++) {
            if (!visited.add(cursor)) throw new IllegalStateException("分页游标未推进，请刷新");
            Page<T> page = source.apply(cursor);
            for (T item : page.items()) values.put(identity.apply(item), item);
            sequence = Math.min(sequence, page.sequence());
            cursor = page.nextCursor();
            if (cursor.isEmpty() || values.size() >= minimumItems)
                return new Page<>(List.copyOf(values.values()), cursor, sequence);
        }
        throw new IllegalStateException("分页未返回足够数据，请刷新");
    }
}
