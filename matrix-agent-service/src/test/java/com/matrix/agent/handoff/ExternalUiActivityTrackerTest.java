package com.matrix.agent.handoff;
import static org.junit.Assert.*;
import com.matrix.agent.api.handoff.*;
import org.junit.Test;
import java.util.*;

public final class ExternalUiActivityTrackerTest {
    @Test public void nestedAndConcurrentOperationsDoNotPublishFalseIdle() {
        List<ExternalUiActivitySnapshot> events = new ArrayList<>();
        ExternalUiActivityTracker tracker = new ExternalUiActivityTracker(events::add);
        var outer = tracker.begin("one"); var nested = tracker.begin("one"); var other = tracker.begin("two");
        nested.close(); outer.close(); outer.close();
        assertEquals(1, events.size()); assertEquals(HandoffProtocol.AUTOMATION, tracker.snapshot().state());
        other.close(); assertEquals(2, events.size()); assertEquals(HandoffProtocol.IDLE, tracker.snapshot().state());
        assertTrue(events.get(1).generation() > events.get(0).generation());
    }
}
