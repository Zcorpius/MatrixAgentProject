package com.matrix.agent.voice.tencent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless, testable implementation of Tencent Cloud API 3.0's TC3-HMAC-SHA256 signature. */
public final class TencentTc3Signer {
    public static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String SERVICE = "tts";
    private static final String HOST = "tts.tencentcloudapi.com";
    private static final String VERSION = "2019-08-23";
    private static final String ACTION = "TextToVoice";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private TencentTc3Signer() { }

    public static SignedHeaders sign(String secretId, String secretKey, String payload,
            long epochSeconds) throws Exception {
        String date = DATE.format(Instant.ofEpochSecond(epochSeconds));
        String payloadHash = sha256(payload);
        // Tencent's TC3 examples require content-type and host; keep the signed-header surface
        // deliberately minimal and exactly aligned with their public API contract.
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + HOST + "\n";
        String signed = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signed + "\n"
                + payloadHash;
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + epochSeconds + "\n" + credentialScope + "\n"
                + sha256(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = null;
        byte[] secretSigning = null;
        try {
            secretService = hmac(secretDate, SERVICE);
            byte[] secretTerminal = hmac(secretService, "tc3_request");
            try {
                secretSigning = secretTerminal;
                String signature = hex(hmac(secretSigning, stringToSign));
                String authorization = ALGORITHM + " Credential=" + secretId + "/"
                        + credentialScope + ", SignedHeaders=" + signed + ", Signature=" + signature;
                return new SignedHeaders(authorization, Long.toString(epochSeconds));
            } finally {
                if (secretSigning != null) java.util.Arrays.fill(secretSigning, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(secretDate, (byte) 0);
            if (secretService != null) java.util.Arrays.fill(secretService, (byte) 0);
        }
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        return hmac(key, value.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }
    static String sha256(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    public static final class SignedHeaders {
        public final String authorization;
        public final String timestamp;
        SignedHeaders(String authorization, String timestamp) {
            this.authorization = authorization;
            this.timestamp = timestamp;
        }
    }
}
