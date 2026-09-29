package com.matrix.agent.api.handoff;
import com.matrix.agent.api.handoff.IExternalAppHandoffCallback;
interface IExternalAppHandoffService {
    int registerCallback(IExternalAppHandoffCallback callback);
    void unregisterCallback(IExternalAppHandoffCallback callback);
    int acknowledgeHandoff(IExternalAppHandoffCallback callback, String requestId,
            int preparationResult, int reasonCode);
}
