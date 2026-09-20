package com.matrix.agent.api.voice;

/** Completion callback for a credential provisioning operation. No secret is ever echoed. */
oneway interface IVoiceTtsConfigCallback {
    void onTencentTtsConfigured(String clientOperationId, int code);
}
