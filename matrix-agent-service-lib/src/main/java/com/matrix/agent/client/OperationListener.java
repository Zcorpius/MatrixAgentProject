package com.matrix.agent.client;

import com.matrix.agent.api.model.ModelOperationHandle;

/**
 * 模型/下载异步操作的纯 Java listener（两域共用操作回调协议）。
 * 保证每个操作至多回调一次；在门面事件 Handler 上派发。
 */
public interface OperationListener {

    /** errorCode 为 MatrixErrorCode 之一；SUCCESS 表示操作成功。 */
    void onFinished(ModelOperationHandle handle, int errorCode);
}
