package com.matrix.agent.platform.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.IAudioService;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;
import java.util.Objects;

/**
 * Typed bridge to the target ROM's system audio service. The public android.jar masks AudioManager's
 * SystemApi methods in Gradle, while the matching Lineage framework compile stub exposes this AIDL.
 */
final class SystemMediaRouting {
    @FunctionalInterface
    private interface RemoteCall<T> {
        T execute() throws RemoteException;
    }

    private final IAudioService service;

    SystemMediaRouting() {
        service = Objects.requireNonNull(IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE)), "audio service unavailable");
    }

    List<AudioProductStrategy> strategies() {
        return call("read product strategies", service::getAudioProductStrategies);
    }

    boolean prefer(AudioProductStrategy strategy, AudioDeviceAttributes device) {
        return call("set preferred device", () ->
                service.setPreferredDevicesForStrategy(strategy.getId(), List.of(device)) == 0);
    }

    boolean remove(AudioProductStrategy strategy) {
        return call("remove preferred device", () ->
                service.removePreferredDevicesForStrategy(strategy.getId()) == 0);
    }

    List<AudioDeviceAttributes> preferred(AudioProductStrategy strategy) {
        return call("read preferred devices", () ->
                service.getPreferredDevicesForStrategy(strategy.getId()));
    }

    List<AudioDeviceAttributes> routed(AudioAttributes attributes) {
        return call("read routed devices", () -> service.getDevicesForAttributes(attributes));
    }

    private static <T> T call(String operation, RemoteCall<T> action) {
        try {
            return action.execute();
        } catch (RemoteException failure) {
            throw new IllegalStateException("Audio service failed to " + operation, failure);
        }
    }
}
