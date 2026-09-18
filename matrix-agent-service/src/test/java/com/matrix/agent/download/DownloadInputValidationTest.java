package com.matrix.agent.download;

import java.io.IOException;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Guards the file-system and URL boundaries before untrusted market fields reach storage. */
public class DownloadInputValidationTest {

    @Test
    public void modelNameAcceptsOnlySingleSafeDirectoryName() {
        assertTrue(ModelDownloadManager.isSafeModelName("Qwen3-0.6B-MNN"));
        assertTrue(ModelDownloadManager.isSafeModelName("llama_3.2"));
        assertFalse(ModelDownloadManager.isSafeModelName("../outside"));
        assertFalse(ModelDownloadManager.isSafeModelName(".tmp_model"));
        assertFalse(ModelDownloadManager.isSafeModelName("model/name"));
        assertFalse(ModelDownloadManager.isSafeModelName(""));
    }

    @Test
    public void downloadUrlEscapesTheFilePathAndRejectsInvalidRepository() throws Exception {
        String url = ModelScopeClient.downloadUrl("MNN/Qwen3-0.6B-MNN", "tokenizer files/a.json");
        assertTrue(url.contains("FilePath=tokenizer+files%2Fa.json"));
        try {
            ModelScopeClient.downloadUrl("MNN/../../other", "a.bin");
            fail("invalid repository must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            ModelScopeClient.listFiles("MNN/../../other", new okhttp3.OkHttpClient());
            fail("listing must reject invalid repository before opening a connection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void metadataEndpointsMustBeExactHttpsHostsWithoutRedirectIndirection() throws Exception {
        assertEquals("meta.alicdn.com", TrustedHttpsJson.requireEndpoint(
                "https://meta.alicdn.com/data/mnn/apis/model_market.json", "meta.alicdn.com")
                .getHost());
        assertUntrustedEndpoint("http://meta.alicdn.com/market.json");
        assertUntrustedEndpoint("https://evil.example/market.json");
        assertUntrustedEndpoint("https://user@meta.alicdn.com/market.json");
        assertUntrustedEndpoint("https://meta.alicdn.com:443/market.json");
    }

    @Test
    public void remoteMetadataHasBoundedFileAndTotalSizes() throws Exception {
        assertEquals(42L, ModelDownloadManager.checkedTotalBytes(Collections.singletonList(
                new ModelScopeClient.FileInfo("a", "weights/a.bin", 42L))));
        assertRejected(new ModelScopeClient.FileInfo("bad", "weights/bad.bin", -1L));
        assertRejected(new ModelScopeClient.FileInfo("huge", "weights/huge.bin",
                ModelDownloadManager.MAX_FILE_BYTES + 1L));
    }

    private static void assertRejected(ModelScopeClient.FileInfo file) {
        try {
            ModelDownloadManager.checkedTotalBytes(Collections.singletonList(file));
            fail("invalid remote metadata must be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static void assertUntrustedEndpoint(String endpoint) {
        try {
            TrustedHttpsJson.requireEndpoint(endpoint, "meta.alicdn.com");
            fail("endpoint must be rejected: " + endpoint);
        } catch (IllegalArgumentException expected) {
            // Expected.
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }
}
