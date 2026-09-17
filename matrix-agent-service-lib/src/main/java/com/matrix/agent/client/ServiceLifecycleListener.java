package com.matrix.agent.client;

import com.matrix.agent.api.common.ConnectionState;

/** MatrixAgent 连接生命周期回调。所有回调在门面事件 Handler（默认主线程）上派发。 */
public interface ServiceLifecycleListener {

    /**
     * @param state ConnectionState 之一；重连期间会经历 DISCONNECTED → CONNECTING → CONNECTED。
     */
    void onLifecycleChanged(MatrixAgent agent, int state);
}
