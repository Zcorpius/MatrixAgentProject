package com.matrix.agent.api.agent;

import com.matrix.agent.api.agent.AgentTaskEvent;

/** 任务事件回调：一律 oneway；服务端对 callback linkToDeath，RemoteException 即退订。 */
oneway interface IAgentTaskCallback {
    void onTaskEvent(in AgentTaskEvent event);
}
