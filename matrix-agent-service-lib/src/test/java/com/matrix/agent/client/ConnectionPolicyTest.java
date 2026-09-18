package com.matrix.agent.client;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ConnectionPolicyTest {
    @Test public void deathLink_allowsOnlyOneCandidatePerGeneration() {
        DeathLinkCoordinator<Object> links = new DeathLinkCoordinator<>();
        Object first = new Object();
        Object raced = new Object();
        assertTrue(links.reserve(first, 4L, 4L, false));
        assertFalse(links.reserve(raced, 4L, 4L, false));
        assertTrue(links.promote(first, 4L, 4L, false));
        assertFalse(links.reserve(raced, 4L, 4L, false));
        assertSame(first, links.takeLinked());
        assertTrue(links.reserve(raced, 4L, 4L, false));
    }

    @Test public void deathLink_rejectsStaleOrReleasedPromotion() {
        DeathLinkCoordinator<Object> links = new DeathLinkCoordinator<>();
        Object binder = new Object();
        assertFalse(links.reserve(binder, 1L, 2L, false));
        assertFalse(links.reserve(binder, 2L, 2L, true));
        assertTrue(links.reserve(binder, 2L, 2L, false));
        assertFalse(links.promote(binder, 2L, 3L, false));
        assertTrue(links.reserve(new Object(), 3L, 3L, false));
    }

    @Test public void negotiation_isHashAndRangeFailClosed() {
        assertTrue(ContractNegotiationPolicy.isCompatible(1, 2, 4, "abc", 1, 3, "abc"));
        assertFalse(ContractNegotiationPolicy.isCompatible(1, 2, 4, "", 1, 3, "abc"));
        assertFalse(ContractNegotiationPolicy.isCompatible(1, 2, 4, null, 1, 3, "abc"));
        assertFalse(ContractNegotiationPolicy.isCompatible(2, 2, 4, "abc", 1, 3, "abc"));
        assertFalse(ContractNegotiationPolicy.isCompatible(1, 2, 4, "abc", 1, 5, "abc"));
    }

    @Test public void credentialUtf8EncodingDoesNotRequireImmutableSecretString() throws Exception {
        char[] secret = new char[] {'密', '钥', 'A'};
        byte[] encoded = ModelManager.encodeUtf8Secret(secret);
        try {
            assertArrayEquals("UTF-8 bytes must be preserved for the Host pipe",
                    "密钥A".getBytes(StandardCharsets.UTF_8), encoded);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
            java.util.Arrays.fill(secret, '\0');
        }
    }
}
