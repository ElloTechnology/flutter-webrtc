package com.cloudwebrtc.webrtc.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.plugin.common.EventChannel;

public final class AnyThreadSink implements EventChannel.EventSink {

    final private EventChannel.EventSink eventSink;
    final private Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Object> eventQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    public AnyThreadSink(EventChannel.EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public void success(Object o) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            // Main-thread fast path: deliver inline
            eventSink.success(o);
        } else {
            // Off-thread: enqueue and CAS-guard a single drain post
            eventQueue.offer(o);
            if (drainScheduled.compareAndSet(false, true)) {
                handler.post(this::drain);
            }
        }
    }

    private void drain() {
        // reset FIRST so new arrivals can schedule
        drainScheduled.set(false);

        // Collect all queued events into a batch
        ArrayList<Object> batch = new ArrayList<>();
        Object event;
        while ((event = eventQueue.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) return;

        if (batch.size() == 1) {
            // Single event: send unbatched
            eventSink.success(batch.get(0));

            return;
        }

        // Coalesce state-machine events: if the batch contains multiple events
        // of the same state type, only the latest matters — intermediate states
        // are already stale by the time the batch reaches Dart. This is safe
        // because:
        //   - These are all finite state machines; only the current state matters
        //   - The Dart layer caches the state and invokes a callback — receiving
        //     "checking" then "connected" is equivalent to just receiving "connected"
        //   - Under CPU pressure , state flapping can produce bursts of events 
        //     that consume main-thread queue slots needed for data-channel messages
        // Note: ICE *candidates* (onCandidate) are NOT coalesced — each candidate
        // is unique and required for connectivity establishment.
        coalesceByEventType(batch, "iceConnectionState");
        coalesceByEventType(batch, "iceGatheringState");
        coalesceByEventType(batch, "signalingState");
        coalesceByEventType(batch, "peerConnectionState");
        // onSelectedCandidatePairChanged is also state-like — only the
        // current active candidate pair matters, not intermediate selections.
        coalesceByEventType(batch, "onSelectedCandidatePairChanged");
        // onRenegotiationNeeded is an idempotent trigger — if N arrive in
        // one batch, the Dart side only needs to act on it once. The
        // resulting createOffer() will cover all pending changes.
        coalesceByEventType(batch, "onRenegotiationNeeded");

        // Multiple events: single platform channel crossing
        eventSink.success(batch);
    }

    /**
     * Removes all but the last event with the given "event" type from the batch.
     * This keeps the final (most recent) state and drops intermediate transitions
     * that the Dart side would just overwrite anyway.
     *
     * For example, if the batch contains:
     *   [iceConnectionState=checking, onMessage, iceConnectionState=connected, onMessage]
     * After coalescing "iceConnectionState":
     *   [onMessage, iceConnectionState=connected, onMessage]
     * The "checking" event is dropped since "connected" supersedes it.
     */
    private static void coalesceByEventType(ArrayList<Object> batch, String eventType) {
        // Find the index of the last event of this type
        int lastIndex = -1;
        for (int i = batch.size() - 1; i >= 0; i--) {
            Object item = batch.get(i);
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                if (eventType.equals(map.get("event"))) {
                    lastIndex = i;
                    break;
                }
            }
        }

        if (lastIndex < 0) return; // no events of this type

        // Remove all earlier events of this type, keeping the last one
        Iterator<Object> it = batch.iterator();
        int idx = 0;
        while (it.hasNext()) {
            Object item = it.next();
            if (idx < lastIndex && item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                if (eventType.equals(map.get("event"))) {
                    it.remove();
                    lastIndex--; // adjust since we removed an element before it
                }
            }
            idx++;
        }
    }

    @Override
    public void error(String s, String s1, Object o) {
        post(()->eventSink.error(s, s1, o));
    }

    @Override
    public void endOfStream() {
        post(eventSink::endOfStream);
    }

    private void post(Runnable r) {
        if(Looper.getMainLooper() == Looper.myLooper()){
            r.run();
        }else{
            handler.post(r);
        }
    }
}
