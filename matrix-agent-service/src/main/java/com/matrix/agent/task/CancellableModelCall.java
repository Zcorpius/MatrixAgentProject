package com.matrix.agent.task;

/**
 * 可取消的 ModelCall 抽象——把"调用 LLM"和"abort 传输层"分离成两个操作。
 *
 * <p>背景:ModelCallExecutor 用 50ms polling + Future.cancel(true) 包裹 gateway.decide,
 * 但 Future.cancel(true) 只发 thread interrupt，真实传输层必须自行注册能关闭 socket 的
 * abort hook 才能立即停止。把 abort 语义从 ModelCallExecutor 下沉到 ModelGateway，
 * 由实现决定如何中止实际 I/O（远端模型路径使用 OkHttp {@code Call.cancel()}）。
 *
 * <p>契约:
 * <ul>
 *   <li>{@link #call()} 同步阻塞,返回 ModelTurn 或抛异常;</li>
 *   <li>{@link #abort()} 由外部(通过 CancellationToken abort hook)异步触发,
 *       必须能在另一个线程调用,目标是让 call() 尽快返回(底层断 socket / 取消 future);</li>
 *   <li>abort() 必须幂等——多次调用与一次调用效果一致;</li>
 *   <li>abort() 不能抛异常(吞掉,只 log),保证 cancel 流程不被一个失败的 abort 阻塞。</li>
 * </ul>
 *
 * <p>默认实现(见 {@link ModelGateway#prepare}):不挂真传输层 abort,abort() 仅记录 intent。
 * 真正生效需要 ModelGateway 实现方覆盖 prepare(),返回带 abort 能力的 CancellableModelCall。
 *
 * <p>远端模型网关将 abort hook 接到 OkHttp {@code Call.cancel()}，让 socket read
 * 立即抛 IOException，而不是等待 read timeout。
 */
public interface CancellableModelCall {
    /** 同步阻塞返回 ModelTurn。 */
    ModelTurn call();

    /**
     * 异步 abort——从外部线程调用,目标是让 {@link #call()} 尽快返回。
     * 必须幂等,不能抛异常。
     */
    void abort();
}
