package com.cloudwebrtc.webrtc.audio;

import androidx.annotation.NonNull;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.Arrays;

/**
 * Process-wide listeners for every buffer the microphone records, before
 * WebRTC's audio processing.
 *
 * The listeners outlive any one audio device module: each module the plugin
 * builds forwards to {@link #samplesReadyCallback()}, so a listener added
 * before the peer connection factory exists, or before another engine builds
 * a new one, still receives every buffer. The types are plain Java, so an
 * embedder can listen without compiling against the WebRTC SDK.
 */
public final class RecordSamplesListeners {

    /** Receives one recorded buffer. */
    public interface Listener {
        /**
         * Runs on the audio record thread for every buffer, typically every
         * 10 ms, so it must not block, allocate, or post to another thread
         * per call. {@code audioFormat} is an {@code android.media.AudioFormat}
         * encoding, and {@code data} holds interleaved samples in that
         * encoding; it must not be kept after the call returns.
         */
        void onRecordSamples(int audioFormat, int channelCount, int sampleRate, @NonNull byte[] data);
    }

    private static final Object lock = new Object();

    // Copy-on-write array, so the record thread iterates without locking or
    // allocating an iterator; listeners change rarely.
    private static volatile Listener[] listeners = new Listener[0];

    private static final JavaAudioDeviceModule.SamplesReadyCallback samplesReadyCallback =
            samples -> {
                final Listener[] current = listeners;
                if (current.length == 0) {
                    return;
                }
                final int audioFormat = samples.getAudioFormat();
                final int channelCount = samples.getChannelCount();
                final int sampleRate = samples.getSampleRate();
                final byte[] data = samples.getData();
                for (Listener listener : current) {
                    listener.onRecordSamples(audioFormat, channelCount, sampleRate, data);
                }
            };

    private RecordSamplesListeners() {}

    public static void add(@NonNull Listener listener) {
        synchronized (lock) {
            final Listener[] next = Arrays.copyOf(listeners, listeners.length + 1);
            next[listeners.length] = listener;
            listeners = next;
        }
    }

    public static void remove(@NonNull Listener listener) {
        synchronized (lock) {
            final Listener[] current = listeners;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == listener) {
                    final Listener[] next = new Listener[current.length - 1];
                    System.arraycopy(current, 0, next, 0, i);
                    System.arraycopy(current, i + 1, next, i, current.length - i - 1);
                    listeners = next;
                    return;
                }
            }
        }
    }

    /** The callback every audio device module the plugin builds forwards to. */
    public static JavaAudioDeviceModule.SamplesReadyCallback samplesReadyCallback() {
        return samplesReadyCallback;
    }
}
