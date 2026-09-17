package com.matrix.agent.api.model;

import com.matrix.agent.api.model.ModelOperationHandle;

/** 模型操作异步结果回调：一律 oneway。 */
oneway interface IModelCallback {
    void onModelOperationFinished(in ModelOperationHandle handle, int errorCode);
}
