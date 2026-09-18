package com.matrix.agent.task;

import com.matrix.agent.session.SessionContext;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.session.*;
import com.matrix.agent.task.tool.ToolResult;


public interface ContextUpdater {
    void onToolCompleted(SessionContext context, ToolCall call, ToolResult result);
}
