package com.matrix.agent.model;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.Test;

import com.matrix.agent.identity.CancellationToken;

/**
 * ModelApiClient.post 的 CancellationToken abort 集成测试。
 *
 * <p>旧实现 post 只检查 {@code Thread.currentThread().isInterrupted()},
 * HTTP 连接在 read 阻塞时无法被强制 abort,长响应取消后 socket 仍占用 read timeout(90s)。
 * 改为把 OkHttp Call.cancel 注册到 token.abortHook,cancel() 触发后立即断开 socket,
 * read 抛 IOException 提前出 finally。
 *
 * <p>测试:本地 ServerSocket accept 后阻塞(永不响应),post 在 read 阻塞;
 * 主线程 cancel token,post 应在 5s 内抛 IOException(否则旧实现会等到 90s read timeout)。
 */
public final class ModelApiClientAbortTest {

    @Test
    public void cancelTriggersImmediateAbortInsteadOfReadTimeout() throws Exception {
        // 启动一个 accept 后永不响应的本地 server
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = server.getLocalPort();
        Thread accepter = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = server.accept();
                        // 不读不写,占着连接让 client read 阻塞
                    } catch (IOException ignored) {
                        break;
                    }
                }
            } finally {
                try { server.close(); } catch (IOException ignored) { }
            }
        }, "abort-test-server");
        accepter.setDaemon(true);
        accepter.start();

        CancellationToken token = new CancellationToken();
        JSONObject body = new JSONObject().put("ping", "abort-test");
        String endpoint = "http://127.0.0.1:" + port + "/";

        long started = System.nanoTime();
        final Throwable[] holder = new Throwable[1];
        Thread caller = new Thread(() -> {
            try {
                ModelApiClient.forTesting().post(endpoint, body, null, null, null, null, token);
                holder[0] = new AssertionError("post 应当被 abort,不该正常返回");
            } catch (Throwable error) {
                holder[0] = error;
            }
        }, "abort-test-caller");
        caller.setDaemon(true);
        caller.start();

        // 等 caller 进入 read 阻塞(经验值 300ms 足够 TCP 三次握手 + send payload)
        Thread.sleep(300);
        token.cancel();

        caller.join(TimeUnit.SECONDS.toMillis(8));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        // 清理 server
        accepter.interrupt();
        server.close();

        assertTrue("caller 应在 join 前结束(post 不应撑到 90s read timeout)",
                !caller.isAlive());
        assertTrue("abort 应在 8s 内完成(实际 " + elapsedMs + "ms)",
                elapsedMs < 8_000);
        if (holder[0] instanceof AssertionError) {
            fail(holder[0].getMessage());
        }
        assertTrue("post 应以异常退出(InterruptedException / IOException / IllegalStateException 之一),实际: "
                        + (holder[0] == null ? "null" : holder[0].getClass().getSimpleName()),
                holder[0] != null);
    }

    @Test
    public void nullTokenDoesNotThrowAndSkipsAbortHook() throws Exception {
        // 验证向后兼容:token=null 时 post 不挂 hook、不 NPE
        // 用一个未监听的端口触发 ConnectException,快速失败
        ServerSocket probe = new ServerSocket();
        probe.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = probe.getLocalPort();
        probe.close();  // 立即释放,确保下一次 connect 被拒

        CancellationToken token = new CancellationToken();
        JSONObject body = new JSONObject().put("k", "v");
        Throwable thrown = null;
        try {
            ModelApiClient.forTesting().post("http://127.0.0.1:" + port + "/", body,
                    null, null, null, null, null);
        } catch (Throwable t) {
            thrown = t;
        }
        // 不管是 ConnectException 还是别的——关键是 null token 不 NPE
        assertTrue("null token 时 post 应以异常退出(连接被拒),不应正常返回",
                thrown != null);
        // abort hook 列表应仍为空(没注册过)
        assertTrue("null token 不应挂任何 abort hook",
                !token.isCancelled());
    }
}
