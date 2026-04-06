package com.cloudwebrtc.webrtc;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import org.webrtc.DataChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import io.flutter.plugin.common.EventChannel;

/**
 * Handles data channel message preprocessing on a background thread
 * and batches delivery to the main thread to reduce main looper pressure.
 *
 * This optimization addresses the critical bottleneck where every data channel
 * message causes a separate handler.post() to the main looper. By batching
 * messages, we reduce main thread contention under high message load.
 *
 * Thread safety:
 * - WebRTC callbacks arrive on arbitrary native threads
 * - Preprocessing happens on a dedicated HandlerThread
 * - Final delivery must happen on main thread (Flutter requirement)
 * - eventSink is volatile for safe publication across threads
 * - pendingBatch uses synchronized blocks for batch management
 * - eventQueue uses CopyOnWriteArrayList for thread-safe queueing
 */
class DataChannelEventDispatcher {

    // Shared background thread for all DataChannel instances (reduces thread overhead)
    private static HandlerThread processingThread;
    private static Handler processingHandler;
    private static final Object threadLock = new Object();

    // Main thread handler for final delivery (Flutter EventChannel requires main thread)
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Reference to the actual EventSink (set when Flutter listener attaches)
    // Volatile for safe publication across threads
    private volatile EventChannel.EventSink eventSink;

    // Queue for events that arrive before Flutter listener attaches
    private final List<Object> eventQueue = new CopyOnWriteArrayList<>();

    // Batching state - synchronized on pendingBatch for thread safety
    private final List<Object> pendingBatch = new ArrayList<>();
    private boolean mainDeliveryScheduled = false;

    // Optimization 2.2b: Object pool for Map reuse - reduces GC pressure
    // Pool is shared across all instances since maps are thread-safe to reuse after clear()
    private static final int MAP_POOL_SIZE = 64;
    private static final Queue<Map<String, Object>> mapPool = new ConcurrentLinkedQueue<>();

    /**
     * Acquires a Map from the pool or creates a new one.
     * Thread-safe via ConcurrentLinkedQueue.
     *
     * @return A cleared Map ready for use
     */
    private static Map<String, Object> acquireMap() {
        Map<String, Object> map = mapPool.poll();
        return (map != null) ? map : new HashMap<>(5); // Pre-size for typical 4-5 fields
    }

    /**
     * Returns a Map to the pool for reuse.
     * Clears the map and adds to pool if not full.
     * Thread-safe via ConcurrentLinkedQueue.
     *
     * @param map The map to return to the pool
     */
    private static void releaseMap(Map<String, Object> map) {
        map.clear();
        if (mapPool.size() < MAP_POOL_SIZE) {
            mapPool.offer(map);
        }
        // If pool is full, let GC collect the map
    }

    /**
     * Ensures the shared background thread is running.
     * Uses double-checked locking for efficient thread creation.
     */
    private static void ensureThread() {
        if (processingHandler != null && processingThread != null && processingThread.isAlive()) {
            return; // Fast path - thread already running
        }
        synchronized (threadLock) {
            if (processingThread == null || !processingThread.isAlive()) {
                processingThread = new HandlerThread(
                    "DataChannelProcessor",
                    Process.THREAD_PRIORITY_DEFAULT
                );
                processingThread.start();
                processingHandler = new Handler(processingThread.getLooper());
            }
        }
    }

    /**
     * Sets the EventSink and flushes any queued events.
     * Called from main thread when Flutter listener attaches via onListen().
     *
     * @param sink The EventSink to use for delivering events to Flutter
     */
    void setEventSink(EventChannel.EventSink sink) {
        this.eventSink = sink;
        if (sink != null && !eventQueue.isEmpty()) {
            // Flush queued events that arrived before listener attached
            for (Object event : eventQueue) {
                sink.success(event);
            }
            eventQueue.clear();
        }
    }

    /**
     * Clears the EventSink reference.
     * Called from main thread when Flutter listener detaches via onCancel().
     */
    void clearEventSink() {
        this.eventSink = null;
    }

    /**
     * Dispatches a data channel message for background processing.
     * Returns immediately - does not block the WebRTC thread.
     *
     * IMPORTANT: The buffer's ByteBuffer may be reused by WebRTC after this
     * method returns, so we must copy the data immediately.
     *
     * @param buffer The message buffer (will be copied, safe to reuse after return)
     * @param dataChannelId The data channel ID for the event payload
     */
    void dispatchMessage(DataChannel.Buffer buffer, int dataChannelId) {
        ensureThread();

        // Capture buffer data immediately (ByteBuffer may be reused by WebRTC)
        final boolean isBinary = buffer.binary;
        final byte[] bytes;
        if (buffer.data.hasArray()) {
            // Buffer backed by array - copy the relevant portion
            byte[] array = buffer.data.array();
            int offset = buffer.data.arrayOffset() + buffer.data.position();
            int length = buffer.data.remaining();
            bytes = new byte[length];
            System.arraycopy(array, offset, bytes, 0, length);
        } else {
            // Direct buffer - copy via get()
            bytes = new byte[buffer.data.remaining()];
            buffer.data.get(bytes);
        }

        // Post preprocessing to background thread - WebRTC thread returns immediately
        processingHandler.post(() ->
            processMessageOnBackgroundThread(bytes, isBinary, dataChannelId)
        );
    }

    /**
     * Dispatches a generic event (state change, buffered amount change).
     * These are less frequent than messages, but still benefit from batching.
     *
     * @param event Pre-built event map to dispatch
     */
    void dispatchEvent(Map<String, Object> event) {
        ensureThread();

        processingHandler.post(() -> {
            synchronized (pendingBatch) {
                pendingBatch.add(event);
                scheduleMainDeliveryIfNeeded();
            }
        });
    }

    /**
     * Processes a message on the background thread.
     * Creates the event map and adds it to the batch for main thread delivery.
     *
     * This work (Map creation, String conversion) is now off the main thread.
     */
    private void processMessageOnBackgroundThread(byte[] bytes, boolean isBinary, int dataChannelId) {
        // Acquire map from pool (2.2b optimization - reduces GC pressure)
        Map<String, Object> params = acquireMap();
        params.put("event", "dataChannelReceiveMessage");
        params.put("id", dataChannelId);

        if (isBinary) {
            params.put("type", "binary");
            params.put("data", bytes);
        } else {
            params.put("type", "text");
            params.put("data", new String(bytes, StandardCharsets.UTF_8));
        }

        // Add to batch and schedule main thread delivery if needed
        synchronized (pendingBatch) {
            pendingBatch.add(params);
            scheduleMainDeliveryIfNeeded();
        }
    }

    /**
     * Schedules main thread delivery if not already scheduled.
     * Must be called while holding pendingBatch lock.
     *
     * This is the key optimization: multiple messages arriving in quick
     * succession result in a single handler.post() to the main thread.
     */
    private void scheduleMainDeliveryIfNeeded() {
        if (!mainDeliveryScheduled && !pendingBatch.isEmpty()) {
            mainDeliveryScheduled = true;
            mainHandler.post(this::deliverBatchOnMainThread);
        }
    }

    /**
     * Delivers all batched messages on the main thread.
     * This is the only place eventSink.success() is called.
     *
     * Called on main thread via mainHandler.post().
     *
     * Optimization 2.2a: Instead of calling sink.success() N times for N messages,
     * we batch all data channel messages into a single event to reduce platform
     * channel crossing overhead.
     */
    @SuppressWarnings("unchecked")
    private void deliverBatchOnMainThread() {
        List<Object> toDeliver;
        synchronized (pendingBatch) {
            if (pendingBatch.isEmpty()) {
                mainDeliveryScheduled = false;
                return;
            }
            toDeliver = new ArrayList<>(pendingBatch);
            pendingBatch.clear();
            mainDeliveryScheduled = false;
        }

        EventChannel.EventSink sink = eventSink;
        if (sink != null) {
            // Separate message events from other events (state changes, buffered amount)
            List<Map<String, Object>> messageEvents = new ArrayList<>();
            List<Object> otherEvents = new ArrayList<>();

            for (Object event : toDeliver) {
                if (event instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) event;
                    if ("dataChannelReceiveMessage".equals(map.get("event"))) {
                        messageEvents.add(map);
                    } else {
                        otherEvents.add(event);
                    }
                } else {
                    otherEvents.add(event);
                }
            }

            // Deliver non-message events individually (state changes, etc.)
            for (Object event : otherEvents) {
                sink.success(event);
            }

            // Deliver all messages as single batch event (2.2a optimization)
            if (!messageEvents.isEmpty()) {
                Map<String, Object> batchEvent = new HashMap<>();
                batchEvent.put("event", "dataChannelBatchReceive");
                batchEvent.put("messages", messageEvents);
                sink.success(batchEvent);  // Single platform channel crossing!

                // Return message maps to pool (2.2b optimization)
                // Safe because StandardMessageCodec copies data during serialization
                for (Map<String, Object> map : messageEvents) {
                    releaseMap(map);
                }
            }
        } else {
            // Queue for later if no Flutter listener yet
            eventQueue.addAll(toDeliver);
        }
    }
}
