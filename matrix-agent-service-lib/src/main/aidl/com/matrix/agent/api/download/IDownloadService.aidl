package com.matrix.agent.api.download;

import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.ModelOperationHandle;

/**
 * 模型市场、下载与模型文件生命周期。
 * listCatalog 只读 Host 私有缓存；refreshCatalog 由 Host 的受限 I/O 队列执行远端刷新。
 */
interface IDownloadService {
    List<ModelCatalogItem> listCatalog();
    List<ModelDownloadInfo> listDownloads();
    ModelOperationHandle refreshCatalog(String clientOperationId, IModelCallback callback);

    ModelOperationHandle install(String catalogModelId, String clientOperationId,
                                 IModelCallback callback);
    ModelOperationHandle pause(String modelId, String clientOperationId);
    ModelOperationHandle resume(String modelId, String clientOperationId,
                                IModelCallback callback);
    /** 删除在 Host I/O 队列中执行；callback 给出唯一的完成终态。 */
    ModelOperationHandle delete(String modelId, String clientOperationId,
                                IModelCallback callback);
}
