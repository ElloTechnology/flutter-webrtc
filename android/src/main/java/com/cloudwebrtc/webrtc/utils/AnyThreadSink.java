package com.cloudwebrtc.webrtc.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.plugin.common.EventChannel;

public final class AnyThreadSink implements EventChannel.EventSink {

    // State-machine and idempotent event types that can be coalesced:
    // only the latest occurrence in a batch matters.
    //   - State-machine types: only the current state matters; intermediate
    //     transitions are stale by the time the batch reaches Dart.
    //   - onSelectedCandidatePairChanged: state-like — only the current
    //     active candidate pair matters, not intermediate selections.
    //   - onRenegotiationNeeded: idempotent trigger — the Dart side only
    //     needs to act once; the resulting createOffer() covers all changes.
    // Note: ICE *candidates* (onCandidate) are NOT coalesced — each
    // candidate is unique and required for connectivity establishment.
    private static final Set<String> COALESCED_EVENT_TYPES = Set.of(
            "iceConnectionState",
            "iceGatheringState",
            "signalingState",
            "peerConnectionState",
            "onSelectedCandidatePairChanged",
            "onRenegotiationNeeded"
    );

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

    // Batches all queued events into a single platform-channel crossing,
    // coalescing redundant state events in O(n). This local expense prevents
    // per-event main-thread dispatches whose looper round-trip cost greatly
    // outweighs the iteration here.
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

        coalesceStateEvents(batch);

        // Multiple events: single platform channel crossing
        eventSink.success(batch);
    }

    /**
     * Removes all but the last event of each coalesced type from the batch
     * in a single O(n) pass. Non-coalesced events are always kept.
     *
     * For example, if the batch contains:
     *   [iceConnectionState=checking, onMessage, iceConnectionState=connected, onMessage]
     * After coalescing:
     *   [onMessage, iceConnectionState=connected, onMessage]
     * The "checking" event is dropped since "connected" supersedes it.
     */
    private static void coalesceStateEvents(ArrayList<Object> batch) {
        // Reverse pass: for each coalesced type, mark only its last occurrence
        // as kept. All earlier duplicates are left unmarked (not set in keep).
        Set<String> seen = new HashSet<>();
        BitSet keep = new BitSet(batch.size());
        for (int i = batch.size() - 1; i >= 0; i--) {
            Object item = batch.get(i);
            if (item instanceof Map<?, ?> map
                    && map.get("event") instanceof String s
                    && COALESCED_EVENT_TYPES.contains(s)) {
                if (!seen.add(s)) continue; // earlier duplicate — don't keep
            }
            keep.set(i);
        }

        if (keep.cardinality() == batch.size()) return; // nothing to coalesce

        // Forward pass: compact in-place, preserving relative order
        int dst = 0;
        for (int src = 0; src < batch.size(); src++) {
            if (keep.get(src)) {
                batch.set(dst++, batch.get(src));
            }
        }
        batch.subList(dst, batch.size()).clear();
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
