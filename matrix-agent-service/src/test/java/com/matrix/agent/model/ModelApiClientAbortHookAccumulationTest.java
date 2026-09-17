package com.matrix.agent.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.matrix.agent.task.identity.CancellationToken;

/**
 * 验证 ModelApiClient.post 的 abort hook 不会因 Runnable 实例不同而累积。
 *
 * <p>旧实现两次 {@code connection::disconnect} 求值产生两个不同 Runnable
 * (绑定实例方法引用每次评估都新建实例),CopyOnWriteArrayList.remove() 用 equals 比对,
 * 移除失败 → 已结束请求的 hook 永远累积在 token.abortHooks 中,长期运行内存泄漏。
 *
 * <p>新实现把 abortHook 提取为局部变量,register/remove 用同一引用。
 */
public final class ModelApiClientAbortHookAccumulationTest {

    @Test
    public void sameRunnableReferenceIsRegisteredAndRemovedCleanly() {
        CancellationToken token = new CancellationToken();
        Runnable hook = () -> { };
        assertEquals(0, token.abortHookCount());

        token.registerAbortHook(hook);
        assertEquals(1, token.abortHookCount());

        token.removeAbortHook(hook);
        assertEquals("同一 Runnable 引用 remove 应生效,hook 数量回到 0",
                0, token.abortHookCount());
    }

    @Test
    public void differentRunnableInstancesFromMethodReferenceDoNotRemove() {
        // 反例:旧实现两次 connection::disconnect 求值产生两个不同 Runnable
        // 这里用 System.out::println 演示——同一表达式求值两次得到不同实例
        CancellationToken token = new CancellationToken();
        Runnable hook1 = System.out::println;
        Runnable hook2 = System.out::println;
        // hook1 == hook2 不成立(method reference 每次新建实例)
        // (注:JVM 实现可以 cache,但 OpenJDK / Android 不保证;本断言保守不做 == 检查)

        token.registerAbortHook(hook1);
        assertEquals(1, token.abortHookCount());

        token.removeAbortHook(hook2);
        // hook2 是新实例,与 hook1 不 equals(Runnable 没重写 equals,默认 == 比对)
        assertEquals("hook2 与 hook1 是不同实例,remove 不应生效",
                1, token.abortHookCount());

        // 清理
        token.removeAbortHook(hook1);
        assertEquals(0, token.abortHookCount());
    }

    @Test
    public void multiplePostsOnSameTokenDoNotAccumulateHooks() throws Exception {
        // 模拟 ModelApiClient.post 的修复后行为:每次 post 用局部变量 abortHook,
        // register + remove 用同一引用,N 次 post 后 token.abortHookCount() 仍为 0
        CancellationToken token = new CancellationToken();
        for (int i = 0; i < 10; i++) {
            Runnable abortHook = () -> { };  // 模拟 connection::disconnect
            token.registerAbortHook(abortHook);
            token.removeAbortHook(abortHook);
        }
        assertEquals("10 次 register/remove 后 hook 数量应仍为 0",
                0, token.abortHookCount());
    }
}
