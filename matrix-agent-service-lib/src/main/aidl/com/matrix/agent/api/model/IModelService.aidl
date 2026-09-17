package com.matrix.agent.api.model;

import com.matrix.agent.api.model.ConnectionTestResult;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.ModelConfigInput;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelOperationHandle;
import com.matrix.agent.api.model.ModelProvisionInput;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import android.os.ParcelFileDescriptor;

/** 模型配置、选择与端侧运行状态；下载传输归 Download Service。 */
interface IModelService {
    List<ModelInfo> listModels();
    ModelRuntimeStatus getRuntimeStatus();

    /** ModelConfigInput 只接受受控 provider 枚举与 Keystore 密钥引用，不接受任意 URL/token。 */
    ConnectionTestResult testConnection(in ModelConfigInput config);

    /**
     * Provision a provider preset with a one-shot secret pipe. The credential bytes are never a
     * Parcel string/Bundle field; Host reads, bounds and immediately encrypts them in its own
     * AndroidKeyStore domain. apiKeyRef subsequently becomes host-keystore:{providerId}.
     */
    ModelOperationHandle provisionCredential(in ModelProvisionInput input, in ParcelFileDescriptor secretPipe,
                                             String clientOperationId, IModelCallback callback);

    ModelOperationHandle setActiveModel(String modelId, String clientOperationId,
                                        IModelCallback callback);
}
