package com.matrix.agent.api.interaction;
import com.matrix.agent.api.interaction.OverlayPointerSample;
oneway interface IOverlayPointerCallback {
    void onPointer(in OverlayPointerSample sample);
    void onAvailabilityChanged(boolean available);
}
