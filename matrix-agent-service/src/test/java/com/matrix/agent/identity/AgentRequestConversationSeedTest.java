package com.matrix.agent.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ConversationSeedContext;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

/** requestId 注入与 conversationSeed 契约（设计文档 §4.3 / §5.4）。 */
public final class AgentRequestConversationSeedTest {

    @Test public void injectedRequestIdIsReused() {
        String stable = UUID.randomUUID().toString();
        AgentRequest request = AgentRequest.builder("指令", Actor.DRIVER)
                .requestId(stable)
                .build();
        assertEquals(stable, request.getRequestId());
    }

    @Test public void invalidRequestIdRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequest.builder("指令", Actor.DRIVER)
                        .requestId("NOT-A-UUID")
                        .build());
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequest.builder("指令", Actor.DRIVER)
                        .requestId(UUID.randomUUID().toString().toUpperCase())
                        .build());
    }

    @Test public void seedRoundTripsAndDefaultsNull() {
        AgentRequest plain = AgentRequest.builder("指令", Actor.DRIVER).build();
        assertNull(plain.getConversationSeed());

        ConversationSeedContext seed = new ConversationSeedContext(List.of(
                AgentMessage.user("上一轮"), AgentMessage.assistant("已处理", List.of())));
        AgentRequest seeded = AgentRequest.builder("继续", Actor.DRIVER)
                .conversationSeed(seed)
                .build();
        assertEquals(2, seeded.getConversationSeed().messages().size());
        assertEquals("上一轮", seeded.getConversationSeed().messages().get(0).getContent());
    }

    @Test public void seedListIsDefensivelyCopied() {
        List<AgentMessage> mutable = new java.util.ArrayList<>();
        mutable.add(AgentMessage.user("a"));
        ConversationSeedContext seed = new ConversationSeedContext(mutable);
        mutable.add(AgentMessage.user("b"));
        assertEquals(1, seed.messages().size());
        assertThrows(UnsupportedOperationException.class,
                () -> seed.messages().add(AgentMessage.user("c")));
        assertTrue(seed.messages().get(0).getContent().equals("a"));
    }
}
