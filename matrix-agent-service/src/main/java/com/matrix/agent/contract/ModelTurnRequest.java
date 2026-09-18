package com.matrix.agent.contract;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单轮模型决策的输入。conversation 中已包含上轮 Observation,本次调用让模型决定下一步。 */
public final class ModelTurnRequest {
    private final AgentRequest agentRequest;
    private final List<AgentMessage> conversation;
    private final List<ToolDefinition> tools;
    private final String systemPrompt;
    private final SessionContext sessionContext;

    public ModelTurnRequest(AgentRequest agentRequest, List<AgentMessage> conversation,
            List<ToolDefinition> tools, String systemPrompt, SessionContext sessionContext) {
        if (agentRequest == null) throw new IllegalArgumentException("agentRequest 不能为空");
        if (conversation == null) throw new IllegalArgumentException("conversation 不能为空");
        if (tools == null) throw new IllegalArgumentException("tools 不能为空");
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        if (sessionContext == null) throw new IllegalArgumentException("sessionContext 不能为空");
        this.agentRequest = agentRequest;
        this.conversation = Collections.unmodifiableList(new ArrayList<>(conversation));
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        this.systemPrompt = systemPrompt;
        this.sessionContext = sessionContext;
    }

    public AgentRequest getAgentRequest() { return agentRequest; }
    public List<AgentMessage> getConversation() { return conversation; }
    public List<ToolDefinition> getTools() { return tools; }
    public String getSystemPrompt() { return systemPrompt; }
    public SessionContext getSessionContext() { return sessionContext; }
}
