package com.matrix.agent.api.handoff;
import com.matrix.agent.api.handoff.ExternalAppHandoffRequest;
import com.matrix.agent.api.handoff.ExternalUiActivitySnapshot;
oneway interface IExternalAppHandoffCallback {
    void onHandoffRequested(in ExternalAppHandoffRequest request);
    void onLaunchAttemptFinished(String requestId, int result, long dispatchedElapsedRealtimeMs);
    void onExternalUiActivityChanged(in ExternalUiActivitySnapshot snapshot);
}
