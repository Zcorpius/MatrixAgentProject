package com.matrix.agent.voice;

import org.junit.Test;

import com.matrix.agent.api.download.ModelDownloadInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link VoskModelDownloader} 回归测试。
 *
 * <p>取消语义(未建连接/PAUSED/无产出)+ isDownloaded(active 指针判定)。
 */
public final class VoskModelDownloaderTest {

    @Test
    public void download_cancelledBeforeConnect_marksPaused_noDownload() throws Exception {
        File root = Files.createTempDirectory("vosk-test").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null); // dao 降级(仅日志)
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf");
        try {
            d.download(spec, () -> true); // 下载前即取消
            fail("已取消应抛异常");
        } catch (IOException e) {
            assertTrue("应是取消异常,实际: " + e.getMessage(), e.getMessage().contains("CANCELLED"));
        }
        assertFalse("取消不应产出模型", d.isDownloaded(spec));
        root.delete();
    }

    @Test
    public void isDownloaded_falseWithoutActive() throws Exception {
        File root = Files.createTempDirectory("vosk-test2").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null);
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf");
        assertFalse("无 active 指针应未就绪", d.isDownloaded(spec));
        root.delete();
    }

    @Test
    public void modelInfo_notInstalled_usesSpecSizeForVisibleProgress() throws Exception {
        File root = Files.createTempDirectory("vosk-info").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null);
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 1234L, 3000L, null);

        ModelDownloadInfo info = d.modelInfo(spec);

        assertEquals(ModelDownloadInfo.DOWNLOAD_STATE_IDLE, info.state);
        assertEquals(0L, info.bytesDownloaded);
        assertEquals(1234L, info.bytesTotal);
        root.delete();
    }

    @Test
    public void delete_removesModelDirectoryAndResumableArchive() throws Exception {
        File root = Files.createTempDirectory("vosk-delete").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null);
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
        File marker = new File(spec.targetDir, "partial/content.bin");
        assertTrue(marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());
        File archive = new File(root, ".tmp_test_0.1.zip");
        assertTrue(archive.createNewFile());

        d.delete(spec);

        assertFalse(spec.targetDir.exists());
        assertFalse(archive.exists());
        root.delete();
    }

    @Test
    public void migrateLegacy_flatThenIsDownloadedTrue() throws Exception {
        File root = Files.createTempDirectory("vosk-mig").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null);
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf");
        // 旧平铺布局:model/conf/mfcc.conf 直接平铺
        File model = new File(root, "model");
        assertTrue(new File(model, "conf").mkdirs());
        assertTrue(new File(model, "conf/mfcc.conf").createNewFile());

        assertFalse("迁移前未就绪", d.isDownloaded(spec));
        assertTrue("应执行迁移", d.migrateLegacy(spec));
        assertTrue("迁移后应就绪", d.isDownloaded(spec));
        root.delete();
    }

    @Test
    public void download_insufficientStorage_rejectedBeforeNetwork() throws Exception {
        File root = Files.createTempDirectory("vosk-storage").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null);
        // installSizeBytes 设 10TB(远超 temp dir 可用空间)→ 预检拒绝,不触网络
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf", "test", "0.1",
                1L, 10L * 1024 * 1024 * 1024 * 1024, null);
        try {
            d.download(spec, () -> false);
            fail("空间不足应抛异常");
        } catch (IOException e) {
            assertTrue("应是空间不足,实际: " + e.getMessage(), e.getMessage().contains("INSUFFICIENT_STORAGE"));
        }
        assertFalse("空间不足不应产出模型", d.isDownloaded(spec));
        root.delete();
    }

    /** P2-3: mini HTTP server(raw socket,Android 单测无 com.sun.net.httpserver),accept 一个请求返回 status。 */
    private static ServerSocket startMiniStatusServer(int status) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                InputStream in = s.getInputStream();
                StringBuilder req = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    req.append((char) b);
                    if (req.toString().endsWith("\r\n\r\n")) break;
                }
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n").getBytes("UTF-8"));
                out.flush();
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /** P2-3: HTTP 503(瞬态)→ PAUSED 保留 tmpZip 断点。 */
    @Test
    public void download_http503_keepsTmpZipBreakpoint() throws Exception {
        ServerSocket server = startMiniStatusServer(503);
        try {
            File root = Files.createTempDirectory("vosk-503").toFile();
            VoskModelDownloader d = new VoskModelDownloader(null);
            VoskModelSpec spec = new VoskModelSpec("test",
                    "http://127.0.0.1:" + server.getLocalPort() + "/m.zip",
                    new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
            File tmpZip = new File(root, ".tmp_test_0.1.zip");
            assertTrue(tmpZip.getParentFile().mkdirs() || tmpZip.getParentFile().exists());
            try (FileOutputStream f = new FileOutputStream(tmpZip)) { f.write(new byte[50]); } // 预置断点
            try {
                d.download(spec, () -> false);
                fail("503 应抛异常");
            } catch (IOException expected) { // PAUSED
                assertTrue("应是瞬态 HTTP_503: " + expected.getMessage(),
                        expected.getMessage().contains("HTTP_503"));
            }
            assertTrue("503(瞬态)应保留 tmpZip 断点", tmpZip.exists());
            assertEquals("503 后断点字节必须不变(未截断)", 50L, tmpZip.length());
        } finally {
            server.close();
        }
    }

    /** P2-3: HTTP 404(永久)→ FAILED 删 tmpZip。 */
    @Test
    public void download_http404_deletesTmpZip() throws Exception {
        ServerSocket server = startMiniStatusServer(404);
        try {
            File root = Files.createTempDirectory("vosk-404").toFile();
            VoskModelDownloader d = new VoskModelDownloader(null);
            VoskModelSpec spec = new VoskModelSpec("test",
                    "http://127.0.0.1:" + server.getLocalPort() + "/m.zip",
                    new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
            File tmpZip = new File(root, ".tmp_test_0.1.zip");
            assertTrue(tmpZip.getParentFile().mkdirs() || tmpZip.getParentFile().exists());
            try (FileOutputStream f = new FileOutputStream(tmpZip)) { f.write(new byte[50]); }
            try {
                d.download(spec, () -> false);
                fail("404 应抛异常");
            } catch (IOException expected) { // FAILED
                assertTrue("应是 HTTP_404: " + expected.getMessage(),
                        expected.getMessage().contains("HTTP_404"));
            }
            assertFalse("404(永久)应删 tmpZip", tmpZip.exists());
        } finally {
            server.close();
        }
    }

    /** P2-3: HTTP 429(限流瞬态)→ PAUSED 保留 tmpZip 断点(字节不变)。 */
    @Test
    public void download_http429_keepsTmpZipBreakpoint() throws Exception {
        ServerSocket server = startMiniStatusServer(429);
        try {
            File root = Files.createTempDirectory("vosk-429").toFile();
            VoskModelDownloader d = new VoskModelDownloader(null);
            VoskModelSpec spec = new VoskModelSpec("test",
                    "http://127.0.0.1:" + server.getLocalPort() + "/m.zip",
                    new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
            File tmpZip = new File(root, ".tmp_test_0.1.zip");
            assertTrue(tmpZip.getParentFile().mkdirs() || tmpZip.getParentFile().exists());
            try (FileOutputStream f = new FileOutputStream(tmpZip)) { f.write(new byte[50]); }
            try {
                d.download(spec, () -> false);
                fail("429 应抛异常");
            } catch (IOException expected) {
                assertTrue("应是瞬态 HTTP_429: " + expected.getMessage(),
                        expected.getMessage().contains("HTTP_429"));
            }
            assertTrue("429(限流)应保留 tmpZip", tmpZip.exists());
            assertEquals("429 后断点字节不变", 50L, tmpZip.length());
        } finally {
            server.close();
        }
    }

    /** P2-3: 连接断开(SocketException/EOF,递归 cause)→ PAUSED 保留断点。 */
    @Test
    public void download_socketReset_keepsTmpZipBreakpoint() throws Exception {
        java.util.concurrent.CountDownLatch accepted = new java.util.concurrent.CountDownLatch(1);
        ServerSocket server = startMiniDisconnectServer(accepted);
        try {
            File root = Files.createTempDirectory("vosk-sock").toFile();
            VoskModelDownloader d = new VoskModelDownloader(null);
            VoskModelSpec spec = new VoskModelSpec("test",
                    "http://127.0.0.1:" + server.getLocalPort() + "/m.zip",
                    new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
            File tmpZip = new File(root, ".tmp_test_0.1.zip");
            assertTrue(tmpZip.getParentFile().mkdirs() || tmpZip.getParentFile().exists());
            try (FileOutputStream f = new FileOutputStream(tmpZip)) { f.write(new byte[50]); }
            try {
                d.download(spec, () -> false);
                fail("断连应抛异常");
            } catch (IOException expected) {
                // P3: 校验异常链含 SocketException/EOFException(确认是网络断连,非别的 IO)
                boolean netCause = false;
                for (Throwable t = expected; t != null; t = t.getCause()) {
                    if (t instanceof java.net.SocketException
                            || t instanceof java.io.EOFException
                            || t instanceof java.net.ConnectException
                            || t instanceof java.net.SocketTimeoutException) {
                        netCause = true;
                        break;
                    }
                }
                assertTrue("异常链应含 SocketException/EOFException: " + expected, netCause);
            }
            assertTrue("本地 server 应 accept 请求", accepted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("SocketException(瞬态)应保留 tmpZip", tmpZip.exists());
            assertEquals("断连后断点字节不变", 50L, tmpZip.length());
        } finally {
            server.close();
        }
    }

    /** A server can omit Content-Length, so the downloader must enforce the size bound while streaming. */
    @Test
    public void download_chunkedBodyBeyondDeclaredSize_rejectedAndCleared() throws Exception {
        ServerSocket server = startMiniBodyServer(new byte[101]);
        try {
            File root = Files.createTempDirectory("vosk-too-large").toFile();
            VoskModelDownloader d = new VoskModelDownloader(null);
            VoskModelSpec spec = new VoskModelSpec("test",
                    "http://127.0.0.1:" + server.getLocalPort() + "/m.zip",
                    new File(root, "model"), "conf/mfcc.conf", "test", "0.1", 100L, 100L, null);
            try {
                d.download(spec, () -> false);
                fail("超过规格大小的无长度响应必须被拒绝");
            } catch (IOException expected) {
                assertTrue("应报告 ZIP_TOO_LARGE: " + expected.getMessage(),
                        expected.getMessage().contains("ZIP_TOO_LARGE"));
            }
            assertFalse("超大下载不得保留可续传的损坏断点",
                    new File(root, ".tmp_test_0.1.zip").exists());
            assertFalse("超大下载不得产出模型", d.isDownloaded(spec));
        } finally {
            server.close();
        }
    }

    /** Returns an HTTP/1.0 close-delimited body so HttpURLConnection reports an unknown length. */
    private static ServerSocket startMiniBodyServer(byte[] body) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                InputStream in = s.getInputStream();
                int previous = -1;
                int current;
                int newlines = 0;
                while ((current = in.read()) != -1) {
                    if (previous == '\r' && current == '\n') {
                        if (++newlines == 2) break;
                    } else if (current != '\r') {
                        newlines = 0;
                    }
                    previous = current;
                }
                OutputStream out = s.getOutputStream();
                out.write("HTTP/1.0 200 OK\r\n\r\n".getBytes("UTF-8"));
                out.write(body);
                out.flush();
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /** accept 后立即关闭连接(不读不写)→ 客户端 read 抛 SocketException/EOFException(Connection reset)。 */
    private static ServerSocket startMiniDisconnectServer(java.util.concurrent.CountDownLatch accepted) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread t = new Thread(() -> {
            try {
                // P2-2: 连续 accept + RST 关闭(防 HttpURLConnection GET 重试卡 30s read timeout)
                for (int i = 0; i < 3 && !ss.isClosed(); i++) {
                    try (Socket s = ss.accept()) {
                        if (i == 0) accepted.countDown();
                        s.setSoLinger(true, 0); // close 发 RST(非 FIN)→ 客户端 SocketException
                        s.getInputStream().read(new byte[1024]); // 让 client 完成 write
                    } catch (IOException ignored) { }
                }
            } finally {
                try { ss.close(); } catch (IOException ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }
}
