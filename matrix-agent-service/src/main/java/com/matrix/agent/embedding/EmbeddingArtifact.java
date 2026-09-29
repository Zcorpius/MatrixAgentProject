package com.matrix.agent.embedding;

import com.matrix.agent.ondevice.OnDeviceEmbedder;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Host-pinned manifest and atomic installation. Untrusted files cannot supply model configuration. */
public final class EmbeddingArtifact {
    public record Part(String name, long bytes, String sha256) { }
    @FunctionalInterface public interface Source { InputStream open(String name) throws IOException; }
    private static final Set<String> FILES = Set.of("config.json", "llm_config.json", "llm.mnn",
            "llm.mnn.weight", "embeddings_bf16.bin", "tokenizer.txt");
    private final File root;
    private final OnDeviceEmbedder.Profile profile;
    private final List<Part> parts;

    public EmbeddingArtifact(File root, String trustedManifest) {
        this.root = root;
        try {
            JSONObject json = new JSONObject(trustedManifest);
            if (json.getInt("schemaVersion") != 1 || !"BERT_EMBEDDING".equals(json.getString("modelType"))) {
                throw new IllegalArgumentException("unsupported embedding artifact");
            }
            profile = new OnDeviceEmbedder.Profile(json.getString("modelVersion"), json.getInt("dimension"),
                    json.getInt("maxTokens"), json.getString("pooling"));
            if (!profile.modelVersion().matches("[A-Za-z0-9._-]{1,96}")) throw new IllegalArgumentException("model version");
            List<Part> parsed = new ArrayList<>();
            Set<String> names = new HashSet<>();
            var files = json.getJSONArray("files");
            long total = 0;
            for (int i = 0; i < files.length(); i++) {
                var file = files.getJSONObject(i);
                Part part = new Part(file.getString("name"), file.getLong("bytes"), file.getString("sha256"));
                if (!FILES.contains(part.name()) || !names.add(part.name()) || part.bytes() <= 0
                        || !part.sha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("artifact part");
                total = Math.addExact(total, part.bytes());
                parsed.add(part);
            }
            if (!names.equals(FILES) || total > 64L * 1024 * 1024) throw new IllegalArgumentException("artifact size");
            parts = List.copyOf(parsed);
        } catch (Exception invalid) { throw new IllegalArgumentException("invalid pinned embedding manifest", invalid); }
    }
    public OnDeviceEmbedder.Profile profile() { return profile; }
    public List<Part> parts() { return parts; }
    public synchronized File verifiedConfig() throws IOException {
        File installed = new File(root, profile.modelVersion());
        verify(installed);
        return new File(installed, "config.json");
    }
    public synchronized File install(Source source, java.util.function.BooleanSupplier cancelled) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("embedding directory unavailable");
        File target = new File(root, profile.modelVersion());
        if (target.exists()) {
            // Never replace an active or partially trusted native model in place.
            return verifiedConfig();
        }
        File staging = new File(root, ".staging-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("embedding staging unavailable");
        try {
            for (Part part : parts) {
                try (InputStream in = source.open(part.name()); FileOutputStream out = new FileOutputStream(new File(staging, part.name()))) {
                    byte[] buffer = new byte[32 * 1024];
                    long remaining = part.bytes();
                    while (remaining > 0) {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("incomplete embedding part");
                        out.write(buffer, 0, read); remaining -= read;
                    }
                    if (in.read() != -1) throw new IOException("oversize embedding part");
                    out.getFD().sync();
                }
            }
            verify(staging);
            if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException();
            Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return new File(target, "config.json");
        } finally {
            // Only this invocation's private staging directory is eligible for cleanup.
            for (Part part : parts) Files.deleteIfExists(new File(staging, part.name()).toPath());
            Files.deleteIfExists(staging.toPath());
        }
    }
    private void verify(File directory) throws IOException {
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) throw new IOException("embedding model absent");
        for (Part part : parts) {
            File file = new File(directory, part.name());
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() != part.bytes()) throw new IOException("embedding part size");
            try (InputStream in = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = new byte[32 * 1024];
                for (int read; (read = in.read(bytes)) >= 0;) digest.update(bytes, 0, read);
                StringBuilder actual = new StringBuilder(64);
                for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (!part.sha256().contentEquals(actual)) throw new IOException("embedding checksum mismatch");
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
}
