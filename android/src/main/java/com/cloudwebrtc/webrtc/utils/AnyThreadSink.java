package com.cloudwebrtc.webrtc.utils;

import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.flutter.plugin.common.EventChannel;

public final class AnyThreadSink implements EventChannel.EventSink {
    private static final String TAG = "FWRTC-Profile";

    // Per-category queue instrumentation counters
    private static final AtomicInteger pendingCount = new AtomicInteger(0);
    private static final AtomicLong dispatchedCount = new AtomicLong(0);
    private static final AtomicLong totalDispatchNanos = new AtomicLong(0);

    /** Returns a snapshot of [pending, dispatched, totalDispatchNanos]. */
    public static long[] snapshot() {
        return new long[]{
            pendingCount.get(),
            dispatchedCount.get(),
            totalDispatchNanos.get()
        };
    }

    final private EventChannel.EventSink eventSink;
    final private Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Object> eventQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    public AnyThreadSink(EventChannel.EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public void success(Object o) {
        Trace.beginSection("FWRTC::AnyThreadSink::success");
        try {
            // Capture t1 (enqueue time) for pipeline-instrumented messages
            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) o;
                if (map.containsKey("_t0_ns")) {
                    map.put("_t1_ns", System.nanoTime());
                    map.put("_pending", (long) pendingCount.get());
                }
            }

            if (Looper.getMainLooper() == Looper.myLooper()) {
                // Main-thread fast path: deliver inline
                long t0 = System.nanoTime();
                eventSink.success(o);
                long elapsed = System.nanoTime() - t0;
                dispatchedCount.incrementAndGet();
                totalDispatchNanos.addAndGet(elapsed);
            } else {
                // Off-thread: enqueue and CAS-guard a single drain post
                eventQueue.offer(o);
                pendingCount.incrementAndGet();
                if (drainScheduled.compareAndSet(false, true)) {
                    handler.post(this::drain);
                }
            }
        } finally {
            Trace.endSection();
        }
    }

    private void drain() {
        drainScheduled.set(false);          // reset FIRST so new arrivals can schedule

        // Collect all queued events into a batch
        ArrayList<Object> batch = new ArrayList<>();
        Object event;
        while ((event = eventQueue.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) return;

        int originalSize = batch.size();

        // Coalesce state-machine events: if the batch contains multiple events
        // of the same state type, only the latest matters — intermediate states
        // are already stale by the time the batch reaches Dart. This is safe
        // because:
        //   - These are all finite state machines; only the current state matters
        //   - The Dart layer caches the state and invokes a callback — receiving
        //     "checking" then "connected" is equivalent to just receiving "connected"
        //   - Under CPU pressure (Redmi A5 compound stress), state flapping
        //     can produce bursts of events that consume main-thread queue slots
        //     needed for latency-sensitive data-channel messages
        // Note: ICE *candidates* (onCandidate) are NOT coalesced — each candidate
        // is unique and required for connectivity establishment.
        if (batch.size() > 1) {
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
        }

        int coalesced = originalSize - batch.size();
        pendingCount.addAndGet(-originalSize);

        if (coalesced > 0) {
            Log.d(TAG, "AnyThreadSink::drain coalesced " + coalesced + " state events (batch " + originalSize + " -> " + batch.size() + ")");
        }

        // Read queue depth once per drain cycle (same Looper frame)
        int queueDepth = MainThreadQueueDepth.get();

        // Stamp per-message metadata
        for (Object item : batch) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                if (map.containsKey("_t0_ns")) {
                    map.put("_t2_ns", System.nanoTime());
                    map.put("_queue_depth", queueDepth);
                    map.put("_batch_size", batch.size());
                }
            }
        }

        long t0 = System.nanoTime();
        if (batch.size() == 1) {
            // Single event: send unbatched (backward compat)
            eventSink.success(batch.get(0));
        } else if (batch.size() > 1) {
            // Multiple events: single platform channel crossing
            eventSink.success(batch);
        }
        long elapsed = System.nanoTime() - t0;
        dispatchedCount.addAndGet(batch.size());
        totalDispatchNanos.addAndGet(elapsed);
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
        Trace.beginSection("FWRTC::AnyThreadSink::error");
        try {
            post(() -> {
                Trace.beginSection("FWRTC::AnyThreadSink::error::mainThread");
                try {
                    eventSink.error(s, s1, o);
                } finally {
                    Trace.endSection();
                }
            });
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void endOfStream() {
        post(() -> {
            Trace.beginSection("FWRTC::AnyThreadSink::endOfStream::mainThread");
            try {
                eventSink.endOfStream();
            } finally {
                Trace.endSection();
            }
        });
    }

    private void post(Runnable r) {
        if(Looper.getMainLooper() == Looper.myLooper()){
            Log.d(TAG, "AnyThreadSink::post already on main thread, running inline");
            long t0 = System.nanoTime();
            r.run();
            long elapsed = System.nanoTime() - t0;
            dispatchedCount.incrementAndGet();
            totalDispatchNanos.addAndGet(elapsed);
        }else{
            Log.d(TAG, "AnyThreadSink::post from thread=" + Thread.currentThread().getName() + " -> posting to main thread");
            pendingCount.incrementAndGet();
            handler.post(() -> {
                pendingCount.decrementAndGet();
                long t0 = System.nanoTime();
                r.run();
                long elapsed = System.nanoTime() - t0;
                dispatchedCount.incrementAndGet();
                totalDispatchNanos.addAndGet(elapsed);
            });
        }
    }
}
