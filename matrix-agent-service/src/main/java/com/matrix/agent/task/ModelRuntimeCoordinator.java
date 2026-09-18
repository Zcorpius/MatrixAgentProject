package com.matrix.agent.task;

import com.matrix.agent.contract.RetirableModelGateway;

import com.matrix.agent.contract.GatewayLifecycleManager;

import android.util.Log;

import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentRuntimeRepository.AgentEngineFactory;
import com.matrix.agent.contract.ModelGateway;

/**
 * 当前模型网关的生命周期边界。
 *
 * <p>负责创建新 Engine、端侧网关退役以及 UI 所需的当前模型状态；不处理任务调度、
 * 用户数据或 Binder 请求。端侧到端侧切换必须同步释放旧 native session，避免双模型
 * 同时占用数 GB 内存。
 */
public final class ModelRuntimeCoordinator {
    private static final String TAG = "MatrixAgent";

    private final AgentEngineFactory engineFactory;
    private RetirableModelGateway lastRetirableGateway;
    private GatewayLifecycleManager lifecycleManager;
    private AgentEngine currentEngine;
    private String activeDisplayName;

    public ModelRuntimeCoordinator(AgentEngineFactory engineFactory, ModelGateway initialGateway,
            String initialDisplayName) {
        if (engineFactory == null) throw new IllegalArgumentException("engineFactory 不能为空");
        this.engineFactory = engineFactory;
        switchGateway(initialGateway, initialDisplayName);
    }

    public synchronized void switchGateway(ModelGateway gateway, String displayName) {
        if (gateway == null) throw new IllegalArgumentException("gateway 不能为空");
        String safeName = displayName == null ? gateway.getClass().getSimpleName() : displayName;
        Log.i(TAG, "[ModelRuntime] switch gateway -> " + safeName
                + " (" + gateway.getClass().getSimpleName() + ")");
        boolean newIsRetirable = gateway instanceof RetirableModelGateway;
        if (lastRetirableGateway != null) {
            if (newIsRetirable) {
                RetirableModelGateway old = lastRetirableGateway;
                old.retire();
                try {
                    if (old.awaitDrained(5_000L)) old.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else if (lifecycleManager != null) {
                lifecycleManager.retireAsync(lastRetirableGateway);
            }
        }
        lastRetirableGateway = newIsRetirable ? (RetirableModelGateway) gateway : null;
        currentEngine = engineFactory.create(gateway);
        activeDisplayName = safeName;
    }

    public synchronized AgentEngine currentEngine() {
        if (currentEngine == null) throw new IllegalStateException("model runtime not initialized");
        return currentEngine;
    }

    public synchronized String activeDisplayName() { return activeDisplayName; }

    public synchronized void setLifecycleManager(GatewayLifecycleManager manager) {
        lifecycleManager = manager;
    }

    public synchronized String lastOnDeviceStats() {
        return lastRetirableGateway == null ? null : lastRetirableGateway.getLastStats();
    }
}
