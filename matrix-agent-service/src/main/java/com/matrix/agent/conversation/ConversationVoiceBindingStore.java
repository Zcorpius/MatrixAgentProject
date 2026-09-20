package com.matrix.agent.conversation;

import android.util.Log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内一次性 PTT 绑定存储（设计文档 §8.1）。
 *
 * <p>Launcher 先经 {@code IConversationService.createVoiceBinding} 取得高熵
 * {@code bindingOperationId}（小写 UUID），再把该 id 原样作为
 * {@code IVoiceService.startUserInitiatedSession} 的 {@code clientOperationId}——
 * 不修改冻结 Parcelable。VoiceServiceStub 在 sessionLock 临界区原子领取。
 *
 * <p>刻意不落 SQLCipher：30 秒一次性令牌写库无必要；进程死亡即全部失效，
 * Launcher 收到失败后重新创建。clearUserData 同步清空 map。</p>
 */
public final class ConversationVoiceBindingStore {

    private static final String TAG = "MatrixAgent";
    private static final long DEFAULT_TTL_MS = 30_000L;

    /** 绑定生命周期：PENDING → CLAIMED（一次性）；EXPIRED/CANCELLED 是判定结果非存储态。 */
    private static final class Binding {
        final String conversationId;
        final String ownerUserId;
        final long createdAtMs;
        volatile boolean claimed;

        Binding(String conversationId, String ownerUserId, long createdAtMs) {
            this.conversationId = conversationId;
            this.ownerUserId = ownerUserId;
            this.createdAtMs = createdAtMs;
        }
    }

    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final long ttlMs;

    public ConversationVoiceBindingStore() {
        this(DEFAULT_TTL_MS);
    }

    public ConversationVoiceBindingStore(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 创建绑定；返回一次性 bindingOperationId（调用方须验证 conversation 归属后再调）。 */
    public String create(String conversationId, String ownerUserId) {
        String bindingOperationId = UUID.randomUUID().toString();
        bindings.put(bindingOperationId,
                new Binding(conversationId, ownerUserId, System.currentTimeMillis()));
        Log.d(TAG, "[Binding] 创建 conv=" + conversationId + " op=" + bindingOperationId);
        return bindingOperationId;
    }

    /**
     * 原子领取：仅在未领取且未过期且 owner 匹配时成功。
     * 同一 id 只能成功一次（设计文档 §8.1-3）。
     *
     * @return 绑定的 conversationId；未找到 / 已领取 / 过期 / owner 不匹配返回 null。
     */
    public String consume(String bindingOperationId, String ownerUserId) {
        Binding binding = bindings.get(bindingOperationId);
        if (binding == null) {
            Log.w(TAG, "[Binding] 领取失败：未找到 op=" + bindingOperationId);
            return null;
        }
        synchronized (binding) {
            if (binding.claimed) {
                Log.w(TAG, "[Binding] 领取失败：已消费 op=" + bindingOperationId);
                return null;
            }
            if (System.currentTimeMillis() - binding.createdAtMs > ttlMs) {
                bindings.remove(bindingOperationId);
                Log.w(TAG, "[Binding] 领取失败：已过期 op=" + bindingOperationId);
                return null;
            }
            if (!binding.ownerUserId.equals(ownerUserId)) {
                Log.w(TAG, "[Binding] 领取失败：owner 不匹配 op=" + bindingOperationId);
                return null;
            }
            binding.claimed = true;
            bindings.remove(bindingOperationId);
            Log.d(TAG, "[Binding] 领取成功 conv=" + binding.conversationId
                    + " op=" + bindingOperationId);
            return binding.conversationId;
        }
    }

    /** clearUserData 覆盖：清空全部绑定。 */
    public void clearAll() {
        int count = bindings.size();
        bindings.clear();
        if (count > 0) {
            Log.i(TAG, "[Binding] 清空 " + count + " 条");
        }
    }

    public int size() {
        return bindings.size();
    }
}
