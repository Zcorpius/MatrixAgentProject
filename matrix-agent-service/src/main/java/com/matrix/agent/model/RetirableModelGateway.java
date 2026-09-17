package com.matrix.agent.model;

import com.matrix.agent.task.ModelGateway;

/**
 * 可退役的 ModelGateway——供 {@code GatewayLifecycleManager} 在切换模型时安全释放端侧 GB 级资源。
 *
 * <p>语义（评审 P0-2）：{@link #retire()} 标记退役、拒绝新请求；{@link #awaitDrained(long)} 阻塞等
 * <b>在途 native 调用返回</b>（不强制 destroy，避免 use-after-free）；归零后 {@link #close()} 释放。
 * 超时未 drained <b>只标记 stuck，绝不 destroy</b>——继续拒绝新请求直到 native 真返回或进程结束。
 *
 * <p>不继承 AutoCloseable（它没有 retire 语义）。{@code GatewayLifecycleManager}（AppContainer 注入 ioPool）
 * 驱动这套生命周期；{@code AgentRuntimeRepository.setModelGateway} 只负责替换，调 manager 退役旧 gateway。
 */
public interface RetirableModelGateway extends ModelGateway {

    /** 标记退役：拒新请求，排队任务被取消，在途任务继续完成。幂等。 */
    void retire();

    /** 当前是否无在途 lease（可安全 close）。 */
    boolean isDrained();

    /**
     * 阻塞等待 drained。超时返回 false（<b>不 destroy</b>，由调用方标 stuck）。返回 true 表示可安全 close。
     */
    boolean awaitDrained(long timeoutMillis) throws InterruptedException;

    /** 释放底层资源（{@code OnDeviceLlm.close}）。必须 drained 后调用。幂等。 */
    void close();
}
