package com.matrix.agent.client;

import com.matrix.agent.api.agent.AgentTaskEvent;

/**
 * Agent 任务事件的纯 Java listener：不抛 RemoteException、不需要继承 Binder Stub。
 * 经 {@code MatrixAgentManager#subscribeTask} 注册，返回的 AutoCloseable close() 即退订。
 */
public interface AgentTaskListener {

    void onTaskEvent(AgentTaskEvent event);
}
