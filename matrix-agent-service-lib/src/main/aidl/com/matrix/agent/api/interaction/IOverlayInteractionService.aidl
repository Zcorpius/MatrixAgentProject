package com.matrix.agent.api.interaction;
import com.matrix.agent.api.interaction.IOverlayPointerCallback;
/** Launcher-only, default-display, ephemeral observation. Renew the same callback every 10s. */
interface IOverlayInteractionService {
    void subscribe(IOverlayPointerCallback callback);
    void unsubscribe(IOverlayPointerCallback callback);
}
