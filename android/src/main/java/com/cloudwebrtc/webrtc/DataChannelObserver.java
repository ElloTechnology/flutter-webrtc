package com.cloudwebrtc.webrtc;

import org.webrtc.DataChannel;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

/**
 * Observer for WebRTC DataChannel events.
 *
 * Optimized in Test 2.2 to use DataChannelEventDispatcher for:
 * - Background preprocessing of messages (off main thread)
 * - Batched delivery to reduce main looper pressure
 * - Non-blocking WebRTC callbacks (immediate return)
 */
class DataChannelObserver implements DataChannel.Observer, EventChannel.StreamHandler {

    private final String flutterId;
    private final DataChannel dataChannel;

    private final EventChannel eventChannel;

    // Event dispatcher handles background processing and batched main thread delivery
    private final DataChannelEventDispatcher dispatcher = new DataChannelEventDispatcher();

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
        // Set the sink on the dispatcher - it handles queued event flushing
        dispatcher.setEventSink(sink);
    }

    @Override
    public void onCancel(Object o) {
        dispatcher.clearEventSink();
    }

    @Override
    public void onBufferedAmountChange(long amount) {
        // Build event map and dispatch through the batching system
        Map<String, Object> params = new HashMap<>();
        params.put("event", "dataChannelBufferedAmountChange");
        params.put("id", dataChannel.id());
        params.put("bufferedAmount", dataChannel.bufferedAmount());
        params.put("changedAmount", amount);
        dispatcher.dispatchEvent(params);
    }

    @Override
    public void onStateChange() {
        // Build event map and dispatch through the batching system
        Map<String, Object> params = new HashMap<>();
        params.put("event", "dataChannelStateChanged");
        params.put("id", dataChannel.id());
        params.put("state", dataChannelStateString(dataChannel.state()));
        dispatcher.dispatchEvent(params);
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer) {
        // Dispatch to background thread for processing + batched delivery
        // This returns immediately - does not block the WebRTC thread
        dispatcher.dispatchMessage(buffer, dataChannel.id());
    }
}
