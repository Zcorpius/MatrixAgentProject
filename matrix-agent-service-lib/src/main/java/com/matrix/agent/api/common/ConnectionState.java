package com.matrix.agent.api.common;

/** MatrixAgent 连接状态（对齐 ServiceLifecycleListener 回调的 state 取值）。 */
public final class ConnectionState {

    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;

    /** 已发起连接但版本协商失败或协商期服务不可用；不自动重连，需客户端重建 MatrixAgent。 */
    public static final int SERVICE_NOT_READY = 3;

    /** 连接建立但调用方未通过 allowlist 校验（仅业务调用会收到 PERMISSION_DENIED）。 */
    public static final int PERMISSION_DENIED = 4;

    private ConnectionState() {
    }
}
