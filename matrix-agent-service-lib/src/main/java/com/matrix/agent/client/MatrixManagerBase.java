package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

/**
 * 四个 Manager 的公共协议：构造时 asInterface，重连钩子由门面派发；
 * RemoteException 统一交门面处理（触发重连并返回默认值），不向调用方泄漏 Binder 异常。
 */
public abstract class MatrixManagerBase {

    protected final MatrixAgent matrixAgent;
    protected volatile IBinder serviceBinder;

    protected MatrixManagerBase(MatrixAgent matrixAgent, IBinder service) {
        this.matrixAgent = matrixAgent;
        this.serviceBinder = service;
    }

    protected final String contextPackageName() {
        return matrixAgent.contextPackageName();
    }

    /** 门面事件 Handler（默认主线程）；listener 回调一律在其上派发。 */
    protected final android.os.Handler eventHandler() {
        return matrixAgent.eventHandler();
    }

    /** Binder death / 断线：清本地 proxy 引用与 listener，不重建（重建由门面派发 connected）。 */
    protected abstract void onMatrixServiceDisconnected();

    /** 重连成功：重新 asInterface 并重注册既有订阅。 */
    protected abstract void onMatrixServiceConnected(IBinder service);

    /** RemoteException 统一处理：登记断线重连，返回调用方指定的默认值。 */
    protected final <T> T handleRemoteException(Throwable e, T defaultValue) {
        return matrixAgent.handleRemoteExceptionFromMatrixService(e, defaultValue);
    }

    protected final void handleRemoteException(Throwable e) {
        matrixAgent.handleRemoteExceptionFromMatrixService(e, null);
    }
}
