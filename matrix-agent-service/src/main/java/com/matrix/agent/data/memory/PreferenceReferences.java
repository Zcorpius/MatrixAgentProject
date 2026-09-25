package com.matrix.agent.data.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/** Stable owner/zone-bound references for legacy keys. Raw legacy names never leave storage. */
public final class PreferenceReferences {
    public static final String LEGACY_PREFIX = "legacy:";
    public static final int MAX_REFERENCE_LENGTH = LEGACY_PREFIX.length() + 64;

    private PreferenceReferences() { }

    public static boolean isAlias(String reference) {
        return reference != null && reference.matches("legacy:[0-9a-f]{64}");
    }

    public static String forKey(MemoryScope scope, String key) {
        if (MemoryKeyCatalog.isPreferenceKey(key)) return key;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length prefixes make the encoding unambiguous even for embedded NUL/control chars.
            for (String field : new String[]{scope.getUserId(), scope.getZone().wireValue(), key}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder hex = new StringBuilder(LEGACY_PREFIX);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Resolve against the current scoped rows. Missing aliases never fall back to raw names. */
    public static String resolve(MemoryScope scope, String reference, Collection<String> keys) {
        if (!isAlias(reference)) return reference;
        String found = null;
        for (String key : keys) {
            if (MemoryKeyCatalog.isPreferenceKey(key) || !reference.equals(forKey(scope, key))) continue;
            if (found != null && !found.equals(key)) throw new IllegalStateException("ambiguous memory reference");
            found = key;
        }
        return found;
    }
}
