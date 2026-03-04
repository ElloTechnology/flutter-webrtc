package com.cloudwebrtc.webrtc;

import android.os.Trace;
import android.util.Log;

import com.cloudwebrtc.webrtc.utils.AnyThreadSink;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;

import org.webrtc.DataChannel;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

class DataChannelObserver implements DataChannel.Observer, EventChannel.StreamHandler {

    private static final String TAG = "FWRTC-Profile";

    private final String flutterId;
    private final DataChannel dataChannel;

    private final EventChannel eventChannel;
    private EventChannel.EventSink eventSink;
    private final ArrayList eventQueue = new ArrayList();

    // Native-side threshold filtering: only dispatch buffered-amount events
    // when the buffered amount crosses below this threshold (matching Web API behavior).
    // -1 means "not set" — all events are dispatched (backwards compatible).
    private volatile long bufferLowThreshold = -1;
    private long lastBufferedAmount = 0;

    void setBufferedAmountLowThreshold(long threshold) {
        this.bufferLowThreshold = threshold;
    }

    DataChannelObserver(BinaryMessenger messenger, String peerConnectionId, String flutterId,
                        DataChannel dataChannel) {
        this.flutterId = flutterId;
        this.dataChannel = dataChannel;
        eventChannel =
                new EventChannel(messenger, "FlutterWebRTC/dataChannelEvent" + peerConnectionId + flutterId);
        eventChannel.setStreamHandler(this);
    }

    private String dataChannelStateString(DataChannel.State dataChannelState) {
        switch (dataChannelState) {
            case CONNECTING:
                return "connecting";
            case OPEN:
                return "open";
            case CLOSING:
                return "closing";
            case CLOSED:
                return "closed";
        }
        return "";
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink sink) {
        Trace.beginSection("FWRTC::DCObserver::onListen");
        try {
            Log.d(TAG, "DCObserver::onListen thread=" + Thread.currentThread().getName() + " flutterId=" + flutterId + " queueSize=" + eventQueue.size());
            eventSink = new AnyThreadSink(sink);
            for(Object event : eventQueue) {
                eventSink.success(event);
            }
            eventQueue.clear();
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onCancel(Object o) {
        eventSink = null;
    }
    
    @Override
    public void onBufferedAmountChange(long amount) {
        Trace.beginSection("FWRTC::DCObserver::onBufferedAmountChange");
        try {
            long buffered = dataChannel.bufferedAmount();
            long threshold = bufferLowThreshold;

            boolean shouldDispatch;
            if (threshold < 0) {
                // No threshold set — dispatch all events (backwards compatible)
                shouldDispatch = true;
            } else {
                // Only dispatch when crossing below the threshold
                shouldDispatch = lastBufferedAmount >= threshold && buffered < threshold;
            }
            lastBufferedAmount = buffered;

            if (!shouldDispatch) return;

            Log.d(TAG, "DCObserver::onBufferedAmountChange thread=" + Thread.currentThread().getName() + " dcId=" + dataChannel.id() + " buffered=" + buffered + " changed=" + amount);
            ConstraintsMap params = new ConstraintsMap();
            params.putString("event", "dataChannelBufferedAmountChange");
            params.putInt("id", dataChannel.id());
            params.putLong("bufferedAmount", buffered);
            params.putLong("changedAmount", amount);
            sendEvent(params);
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onStateChange() {
        Trace.beginSection("FWRTC::DCObserver::onStateChange");
        try {
            String state = dataChannelStateString(dataChannel.state());
            Log.d(TAG, "DCObserver::onStateChange thread=" + Thread.currentThread().getName() + " dcId=" + dataChannel.id() + " state=" + state);
            ConstraintsMap params = new ConstraintsMap();
            params.putString("event", "dataChannelStateChanged");
            params.putInt("id", dataChannel.id());
            params.putString("state", state);
            sendEvent(params);
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer) {
        Trace.beginSection("FWRTC::DCObserver::onMessage");
        try {
            long ts = System.nanoTime();
            ConstraintsMap params = new ConstraintsMap();
            params.putString("event", "dataChannelReceiveMessage");
            params.putInt("id", dataChannel.id());

            byte[] bytes;
            if (buffer.data.hasArray()) {
                bytes = buffer.data.array();
            } else {
                bytes = new byte[buffer.data.remaining()];
                buffer.data.get(bytes);
            }

            if (buffer.binary) {
                params.putString("type", "binary");
                params.putByte("data", bytes);
            } else {
                params.putString("type", "text");
                params.putString("data", new String(bytes, StandardCharsets.UTF_8));
            }

            params.putLong("_t0_ns", ts);
            params.putInt("_raw_size", bytes.length);

            Log.d(TAG, "DCObserver::onMessage thread=" + Thread.currentThread().getName() + " dcId=" + dataChannel.id() + " binary=" + buffer.binary + " size=" + bytes.length + " ts=" + ts);
            sendEvent(params);
        } finally {
            Trace.endSection();
        }
    }

    private void sendEvent(ConstraintsMap params) {
        Trace.beginSection("FWRTC::DCObserver::sendEvent");
        try {
            if (eventSink != null) {
                Log.d(TAG, "DCObserver::sendEvent thread=" + Thread.currentThread().getName() + " -> dispatching via AnyThreadSink");
                eventSink.success(params.toMap());
            } else {
                Log.d(TAG, "DCObserver::sendEvent thread=" + Thread.currentThread().getName() + " -> QUEUED (no eventSink) queueSize=" + (eventQueue.size() + 1));
                eventQueue.add(params.toMap());
            }
        } finally {
            Trace.endSection();
        }
    }
}
