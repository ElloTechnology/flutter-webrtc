package com.cloudwebrtc.webrtc.video;

import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.LocalTrack;

import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.concurrent.CopyOnWriteArrayList;

public class LocalVideoTrack extends LocalTrack implements VideoProcessor {
    public interface ExternalVideoFrameProcessing {
        /**
         * Process a video frame.
         * @param frame
         * @return The processed video frame.
         */
        public abstract VideoFrame onFrame(VideoFrame frame);
    }

    public LocalVideoTrack(VideoTrack videoTrack) {
        super(videoTrack);
    }

    final CopyOnWriteArrayList<ExternalVideoFrameProcessing> processors = new CopyOnWriteArrayList<>();

    public void addProcessor(ExternalVideoFrameProcessing processor) {
        processors.add(processor);
    }

    public void removeProcessor(ExternalVideoFrameProcessing processor) {
        processors.remove(processor);
    }

    private VideoSink sink = null;

    @Override
    public void setSink(@Nullable VideoSink videoSink) {
        sink = videoSink;
    }

    @Override
    public void onCapturerStarted(boolean b) {}

    @Override
    public void onCapturerStopped() {}

    @Override
    public void onFrameCaptured(VideoFrame videoFrame) {
        if (sink != null) {
            for (ExternalVideoFrameProcessing processor : processors) {
                videoFrame = processor.onFrame(videoFrame);
            }
            sink.onFrame(videoFrame);
        }
    }
}
