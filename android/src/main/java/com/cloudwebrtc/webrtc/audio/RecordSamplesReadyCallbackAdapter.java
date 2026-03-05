package com.cloudwebrtc.webrtc.audio;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.concurrent.CopyOnWriteArrayList;

public class RecordSamplesReadyCallbackAdapter
        implements JavaAudioDeviceModule.SamplesReadyCallback {
    public RecordSamplesReadyCallbackAdapter() {}

    // CopyOnWriteArrayList for lock-free iteration on the audio recording thread
    // (onWebRtcAudioRecordSamplesReady), while callbacks are added/removed infrequently.
    final CopyOnWriteArrayList<JavaAudioDeviceModule.SamplesReadyCallback> callbacks = new CopyOnWriteArrayList<>();

    public void addCallback(JavaAudioDeviceModule.SamplesReadyCallback callback) {
        callbacks.add(callback);
    }

    public void removeCallback(JavaAudioDeviceModule.SamplesReadyCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        for (JavaAudioDeviceModule.SamplesReadyCallback callback : callbacks) {
            callback.onWebRtcAudioRecordSamplesReady(audioSamples);
        }
    }
}
