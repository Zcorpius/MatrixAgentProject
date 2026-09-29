package com.matrix.agent.failure;

import com.matrix.agent.data.memory.MemoryScope;
import java.util.List;
import java.util.Set;

/** Storage owns the atomic epoch check, deduplication, retention and owner/zone boundary. */
public interface FailureLessonStore {
    record Entry(MemoryScope scope, String sourceTask, long epoch, long createdAtMillis,
            String capability, FailureLesson lesson) { }
    boolean save(Entry entry);
    List<Entry> recall(MemoryScope scope, long epoch, Set<String> capabilities, long now, int limit);
    void delete(MemoryScope scope);
}
