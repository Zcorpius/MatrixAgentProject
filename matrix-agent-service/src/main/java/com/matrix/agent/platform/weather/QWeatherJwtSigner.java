package com.matrix.agent.platform.weather;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.PrivateKeyFactory;

/** Generates a 15-minute Ed25519 JWT per request; no long-lived bearer token is stored. */
public final class QWeatherJwtSigner {
    private QWeatherJwtSigner() { }
    public static String sign(String keyId, String developerId, String projectId, String pem, long nowSeconds)
            throws WeatherFailure {
        if (!validId(keyId) || !validId(developerId) || !validId(projectId)
                || pem == null || !pem.contains("-----BEGIN PRIVATE KEY-----") || !pem.contains("-----END PRIVATE KEY-----"))
            throw new WeatherFailure("WEATHER_JWT_CONFIG_INVALID");
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            var key = PrivateKeyFactory.createKey(keyBytes);
            if (!(key instanceof Ed25519PrivateKeyParameters ed25519)) throw new IllegalArgumentException("wrong key type");
            long issued = nowSeconds - 30, expires = nowSeconds + 870;
            String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + keyId + "\"}";
            String payload = "{\"iss\":\"" + developerId + "\",\"sub\":\"" + projectId
                    + "\",\"iat\":" + issued + ",\"exp\":" + expires + "}";
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String data = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            Ed25519Signer signer = new Ed25519Signer(); signer.init(true, ed25519);
            byte[] payloadBytes = data.getBytes(StandardCharsets.US_ASCII);
            signer.update(payloadBytes, 0, payloadBytes.length);
            return data + "." + encoder.encodeToString(signer.generateSignature());
        } catch (Exception invalid) { throw new WeatherFailure("WEATHER_JWT_UNAVAILABLE"); }
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,64}"); }
}
