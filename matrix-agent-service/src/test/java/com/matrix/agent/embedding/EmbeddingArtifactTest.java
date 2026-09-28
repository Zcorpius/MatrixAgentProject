package com.matrix.agent.embedding;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public final class EmbeddingArtifactTest {
    private static final List<String> NAMES = List.of("config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight", "embeddings_bf16.bin", "tokenizer.txt");
    @Test public void verifiesPinnedBytesAndInstallsAtomically() throws Exception {
        File root = Files.createTempDirectory("embedding-artifact").toFile();
        try {
            EmbeddingArtifact artifact = new EmbeddingArtifact(root, manifest());
            assertThrows(IOException.class, artifact::verifiedConfig);
            File config = artifact.install(name -> new ByteArrayInputStream(name.getBytes()), () -> false);
            assertEquals(config, artifact.verifiedConfig());
            assertEquals(1, Objects.requireNonNull(root.list()).length);
            Files.write(config.toPath(), new byte[]{1});
            assertThrows(IOException.class, artifact::verifiedConfig);
        } finally { remove(root); }
    }
    @Test public void shortOversizeWrongDigestAndCancellationLeaveNoIndexableInstallation() throws Exception {
        for (String kind : List.of("short", "oversize", "digest", "cancel")) {
            File root = Files.createTempDirectory("embedding-reject").toFile();
            try {
                var artifact = new EmbeddingArtifact(root, manifest());
                assertThrows(IOException.class, () -> artifact.install(name -> new ByteArrayInputStream(switch (kind) {
                    case "short" -> new byte[0];
                    case "oversize" -> new byte[500];
                    case "digest" -> new byte[name.length()];
                    default -> name.getBytes();
                }), () -> kind.equals("cancel")));
                assertEquals(0, Objects.requireNonNull(root.list()).length);
            } finally { remove(root); }
        }
    }
    static String manifest() throws Exception {
        JSONArray files = new JSONArray();
        for (String name : NAMES) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            files.put(new JSONObject().put("name", name).put("bytes", name.length()).put("sha256", hex));
        }
        return new JSONObject().put("schemaVersion", 1).put("modelType", "BERT_EMBEDDING")
                .put("modelVersion", "test-v1").put("dimension", 2).put("maxTokens", 512)
                .put("pooling", "CLS_L2").put("files", files).toString();
    }
    private static void remove(File path) throws Exception {
        if (path.isDirectory()) for (File child : Objects.requireNonNull(path.listFiles())) remove(child);
        Files.deleteIfExists(path.toPath());
    }
}
