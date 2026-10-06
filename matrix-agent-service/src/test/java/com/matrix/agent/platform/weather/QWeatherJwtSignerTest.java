package com.matrix.agent.platform.weather;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public final class QWeatherJwtSignerTest {
    @Test public void signsShortLivedEd25519TokenWithoutEmbeddingPrivateKey() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        long now = 1_800_000_000L;
        String token = QWeatherJwtSigner.sign("K123", "Q12345ABCD", "P123", pem, now);
        String[] parts = token.split("\\."); assertEquals(3, parts.length);
        var header = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        var payload = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertEquals("EdDSA", header.getString("alg")); assertEquals("K123", header.getString("kid"));
        assertEquals(now - 30, payload.getLong("iat")); assertEquals(now + 870, payload.getLong("exp"));
        Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertFalse(token.contains("PRIVATE KEY"));
    }
}
