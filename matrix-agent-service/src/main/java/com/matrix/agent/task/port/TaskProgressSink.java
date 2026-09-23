package com.matrix.agent.task.port;

/**
 * 任务运行阶段出站端口（输入交互增强 I3，设计文档 §6.2）：task 域向 conversation
 * 域发布受限进度的唯一正式通道——debug trace 是调试旁路，不能充当量产 UI 数据源。
 *
 * <p>形式对齐 {@link TaskAuditSink}/{@link TaskMemoryWriter}：Engine 级一次性装配
 * （{@code AgentEngineConfiguration}），事件携带既有 {@code runtimeRequestId}，由
 * conversation 侧桥接器解析映射后投影为对话阶段。两个观测点是刻意 minimal 的：
 * “进入模型前”与“进入能力执行前”——不把一次模型调用拆成理解/规划两段，也不假装
 * Provider 内部同步 readback 是独立可观测阶段。</p>
 *
 * <p>实现必须自 fail-open：进度事件失败不得影响任务执行。Engine 在 keyed lane
 * worker 线程调用，实现方自行保证线程安全。</p>
 */
public interface TaskProgressSink {

    /** AgentEngine 每次进入模型调用前发布（LlmPlanner / ModelGateway.decide）。
     *  ConversationCompressor 的功能型摘要调用不发布——它不是用户请求的规划回合，
     *  且会造成 EXECUTING→PLANNING 的误导性倒退。FORCE_TOOL 跳过模型，同样不发布。 */
    void onModelPlanning(String runtimeRequestId);

    /** 每个 ToolExecutor.execute 前发布；capabilityName 为能力名（非参数、非结果）。 */
    void onCapabilityExecuting(String runtimeRequestId, String capabilityName);

    /** 安全默认：未装配即不发布任何阶段（行为与端口引入前完全一致）。 */
    TaskProgressSink NOOP = new TaskProgressSink() {
        @Override public void onModelPlanning(String runtimeRequestId) { }
        @Override public void onCapabilityExecuting(String runtimeRequestId,
                String capabilityName) { }
    };
}
