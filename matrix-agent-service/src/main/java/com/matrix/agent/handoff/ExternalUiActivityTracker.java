package com.matrix.agent.handoff;

import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.handoff.ExternalUiActivitySnapshot;
import com.matrix.agent.api.handoff.HandoffProtocol;
import com.matrix.agent.platform.media.ExternalAppHandoffPort.Operation;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Nested handles do not own the outer operation. Concurrent operations keep AUTOMATION active. */
public final class ExternalUiActivityTracker {
    private final Set<String> active = new HashSet<>();
    private long generation;
    private final Consumer<ExternalUiActivitySnapshot> listener;
    public ExternalUiActivityTracker(Consumer<ExternalUiActivitySnapshot> listener) {
        this.listener = listener;
    }
    public Operation begin(String operationId) {
        ExternalUiActivitySnapshot changed;
        synchronized (this) {
            if (!active.add(operationId)) return () -> {};
            changed = active.size() == 1 ? changed() : null;
        }
        if (changed != null) listener.accept(changed);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            ExternalUiActivitySnapshot idle;
            synchronized (this) {
                active.remove(operationId);
                idle = active.isEmpty() ? changed() : null;
            }
            if (idle != null) listener.accept(idle);
        };
    }
    private ExternalUiActivitySnapshot changed() { generation++; return snapshot(); }
    public synchronized ExternalUiActivitySnapshot snapshot() {
        return new ExternalUiActivitySnapshot(ParcelSchema.CURRENT, 0, generation,
                active.isEmpty() ? HandoffProtocol.IDLE : HandoffProtocol.AUTOMATION);
    }
}
