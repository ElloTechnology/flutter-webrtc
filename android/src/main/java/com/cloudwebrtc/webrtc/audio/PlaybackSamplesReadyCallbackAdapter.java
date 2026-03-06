package com.cloudwebrtc.webrtc.audio;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.concurrent.CopyOnWriteArrayList;

public class PlaybackSamplesReadyCallbackAdapter
        implements JavaAudioDeviceModule.PlaybackSamplesReadyCallback {
    public PlaybackSamplesReadyCallbackAdapter() {}

    // CopyOnWriteArrayList for lock-free iteration on the audio playback thread
    // (onWebRtcAudioTrackSamplesReady), while callbacks are added/removed infrequently.
    final CopyOnWriteArrayList<JavaAudioDeviceModule.PlaybackSamplesReadyCallback> callbacks = new CopyOnWriteArrayList<>();

    public void addCallback(JavaAudioDeviceModule.PlaybackSamplesReadyCallback callback) {
        callbacks.add(callback);
    }

    public void removeCallback(JavaAudioDeviceModule.PlaybackSamplesReadyCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public void onWebRtcAudioTrackSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        for (JavaAudioDeviceModule.PlaybackSamplesReadyCallback callback : callbacks) {
            callback.onWebRtcAudioTrackSamplesReady(audioSamples);
        }
    }
}
