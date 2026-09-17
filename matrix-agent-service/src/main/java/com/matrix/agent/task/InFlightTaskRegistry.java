package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.identity.CancellationToken;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 统一管理在途任务的取消与有界收敛，供调度与用户数据重置共享。 */
public final class InFlightTaskRegistry {
    private static final String TAG = "MatrixAgent";
    private final Set<CancellationToken> tokens = ConcurrentHashMap.newKeySet();

    public void register(CancellationToken token) { if (token != null) tokens.add(token); }
    public void unregister(CancellationToken token) { if (token != null) tokens.remove(token); }
    public int size() { return tokens.size(); }
    public boolean isEmpty() { return tokens.isEmpty(); }

    public void cancelAll() {
        for (CancellationToken token : tokens) {
            try { token.cancel(); } catch (Throwable ignored) { }
        }
    }

    /** 等待至多 timeoutMillis；epoch gate 负责拒绝未能及时收敛的旧写入。 */
    public void awaitDrain(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!tokens.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!tokens.isEmpty()) {
            Log.w(TAG, "[TaskRegistry] " + tokens.size()
                    + " task(s) remain after cancellation grace; epoch gate will reject stale writes");
        }
    }
}
