package com.matrix.agent.api.agent;

import android.os.IBinder;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentServiceInfo;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.ConfirmationDecision;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.api.agent.SteerRequest;

/**
 * Agent Manager 根 Binder：任务入口 + 其余三个 Matrix Service 的发现入口。
 *
 * <p>唯一注册到 ServiceManager 的全局 Binder；Model/Voice/Download 的 Binder 只在
 * 服务端 ServiceRegistry 注册，经 {@link #getMatrixService} 分发。
 * Binder 线程只做鉴权、校验、幂等查找、短事务与异步投递，不执行模型/网络/下载。
 */
interface IMatrixAgentManager {
    /** 版本协商载体：客户端取得 Binder 后先协商，无版本交集不得继续业务调用。 */
    AgentServiceInfo getServiceInfo();

    /** 按服务名常量取子 Binder（MatrixServiceConstants 的四个发现键之一）。 */
    IBinder getMatrixService(String serviceName);

    AgentTaskHandle submit(in AgentRequest request, IAgentTaskCallback callback);

    AgentTaskSnapshot getTaskSnapshot(String taskId);

    /** 按 lastSequence 续订；callback 注册即由服务端 linkToDeath。 */
    void subscribeTask(String taskId, long afterSequence, IAgentTaskCallback callback);

    void unsubscribeTask(String taskId, IAgentTaskCallback callback);

    AgentOperationResult cancelTask(String taskId, String clientOperationId);
    AgentOperationResult steerTask(String taskId, in SteerRequest request,
                                   String clientOperationId);
    AgentOperationResult respondConfirmation(String taskId,
                                             in ConfirmationDecision decision,
                                             String clientOperationId);
    AgentOperationResult resumeTask(String taskId, String clientOperationId);
}
