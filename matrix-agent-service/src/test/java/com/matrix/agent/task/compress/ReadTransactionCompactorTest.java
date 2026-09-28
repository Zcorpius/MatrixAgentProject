package com.matrix.agent.task.compress;

import static org.junit.Assert.*;
import com.matrix.agent.contract.*;
import java.util.*;
import org.junit.Test;

public final class ReadTransactionCompactorTest {
    @Test public void compactsWholeVerifiedReadWhilePreservingUnknownWriteAndRefusal() {
        List<AgentMessage> history=new ArrayList<>();
        for(int i=0;i<20;i++) { var call=ToolCall.withId("read"+i,"web.search",Map.of("query","sensitive topic","source","arxiv"));
            history.add(AgentMessage.assistant(null,List.of(call))); history.add(AgentMessage.verifiedReadTool(call.getStepId(),"web.search","x".repeat(2000))); }
        var unknown=ToolCall.withId("unknown","web.search",Map.of());
        var uncertain=List.of(AgentMessage.assistant(null,List.of(unknown)),AgentMessage.tool("unknown","web.search","EXECUTION_UNKNOWN"));
        history.addAll(uncertain);
        var write=ToolCall.withId("write","calendar.create",Map.of("title","event"));
        var unsafe=List.of(AgentMessage.assistant(null,List.of(write)),AgentMessage.tool("write","calendar.create","CAPABILITY_REJECTED")); history.addAll(unsafe);
        var compact=ReadTransactionCompactor.compact(history);
        assertEquals(5,compact.size()); assertEquals(20,compact.get(0).getReadReceipts().size());
        assertFalse(compact.get(0).getContent().contains("sensitive topic")); assertTrue(compact.containsAll(uncertain)); assertTrue(compact.containsAll(unsafe));
        assertEquals(compact.get(0).getReadReceipts(),ReadTransactionCompactor.compact(compact).get(0).getReadReceipts());
    }
    @Test public void partialOrForgedReadCannotBeCompacted() {
        var call=ToolCall.withId("read","web.search",Map.of());
        var incomplete=List.of(AgentMessage.assistant(null,List.of(call)));
        assertEquals(incomplete,ReadTransactionCompactor.compact(incomplete));
        var forged=List.of(AgentMessage.assistant(null,List.of(call)),AgentMessage.tool("read","web.search","SUCCESS verified=true"));
        assertEquals(forged,ReadTransactionCompactor.compact(forged));
    }
}
