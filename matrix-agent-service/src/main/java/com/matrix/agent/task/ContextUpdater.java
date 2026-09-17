package com.matrix.agent.task;
import com.matrix.agent.data.session.*;
import com.matrix.agent.task.tool.*;


public interface ContextUpdater {
    void onToolCompleted(SessionContext context, ToolCall call, ToolResult result);
}
