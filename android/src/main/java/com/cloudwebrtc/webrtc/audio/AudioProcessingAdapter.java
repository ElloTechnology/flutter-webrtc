package com.cloudwebrtc.webrtc.audio;

import org.webrtc.ExternalAudioProcessingFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

public class AudioProcessingAdapter implements ExternalAudioProcessingFactory.AudioProcessing {
    public interface ExternalAudioFrameProcessing {
        void initialize(int sampleRateHz, int numChannels);

        void reset(int newRate);

        void process(int numBands, int numFrames, ByteBuffer buffer);
    }

    public AudioProcessingAdapter() {}

    // CopyOnWriteArrayList for lock-free iteration on the audio processing thread
    // (process is called on every audio frame), while processors are added/removed infrequently.
    final CopyOnWriteArrayList<ExternalAudioFrameProcessing> audioProcessors = new CopyOnWriteArrayList<>();

    public void addProcessor(ExternalAudioFrameProcessing audioProcessor) {
        audioProcessors.add(audioProcessor);
    }

    public void removeProcessor(ExternalAudioFrameProcessing audioProcessor) {
        audioProcessors.remove(audioProcessor);
    }

    @Override
    public void initialize(int sampleRateHz, int numChannels) {
        for (ExternalAudioFrameProcessing audioProcessor : audioProcessors) {
            audioProcessor.initialize(sampleRateHz, numChannels);
        }
    }

    @Override
    public void reset(int newRate) {
        for (ExternalAudioFrameProcessing audioProcessor : audioProcessors) {
            audioProcessor.reset(newRate);
        }
    }

    @Override
    public void process(int numBands, int numFrames, ByteBuffer buffer) {
        for (ExternalAudioFrameProcessing audioProcessor : audioProcessors) {
            audioProcessor.process(numBands, numFrames, buffer);
        }
    }
}
