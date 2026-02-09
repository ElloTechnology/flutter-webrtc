# Ello Modifications to flutter_webrtc

This document tracks all modifications made to the `flutter_webrtc` package (v1.3.0) for performance optimization on low-end Android devices.

## Problem Statement

On low-end Android devices under CPU pressure, WebRTC data channel messages experience severe latency degradation (RTT climbing from ~5s to ~20s within 30 seconds when microphone streaming is enabled).

## Summary of Changes

| File | Change | Impact |
|------|--------|--------|
| `LocalAudioTrack.java` | CopyOnWriteArrayList | -35% avg RTT |
| `DataChannelObserver.java` | CopyOnWriteArrayList + dispatcher integration | -30% avg RTT |
| `DataChannelEventDispatcher.java` | NEW: Background thread + batching | -82% avg RTT |
| `rtc_data_channel_impl.dart` | Batch event handler | Dart-side batch processing |
| `PeerConnectionObserver.java` | Check send() return value + retry logic | RPC reliability |
| `MethodCallHandlerImpl.java` | Return send success/failure to Dart | Error propagation |

**Cumulative improvement: -92% avg RTT** (14.3s → 1.2s)

---

## Modified Files

### 1. LocalAudioTrack.java

**Path:** `android/src/main/java/com/cloudwebrtc/webrtc/audio/LocalAudioTrack.java`

**Change:** Replaced `ArrayList` with `CopyOnWriteArrayList` for thread-safe audio sink iteration.

**Before:**
```java
private final ArrayList<AudioTrackSink> sinks = new ArrayList<>();

public void onData(byte[] audioData, ...) {
    synchronized (sinks) {
        for (AudioTrackSink sink : sinks) {
            sink.onData(audioData, ...);
        }
    }
}
```

**After:**
```java
private final List<AudioTrackSink> sinks = new CopyOnWriteArrayList<>();

public void onData(byte[] audioData, ...) {
    for (AudioTrackSink sink : sinks) {
        sink.onData(audioData, ...);
    }
}
```

**Why:** Eliminates lock contention between WebRTC audio thread and main thread, even when the sinks list is empty.

---

### 2. DataChannelObserver.java

**Path:** `android/src/main/java/com/cloudwebrtc/webrtc/DataChannelObserver.java`

**Changes:**
1. Replaced `ArrayList` with `CopyOnWriteArrayList` for thread-safe event queue
2. Integrated with `DataChannelEventDispatcher` for batched message delivery

**Before:**
```java
private final ArrayList eventQueue = new ArrayList();
// Direct eventSink.success() calls on main thread
```

**After:**
```java
private final List<Object> eventQueue = new CopyOnWriteArrayList<>();
// Delegates to DataChannelEventDispatcher for batched delivery
```

**Why:** Fixes race condition between WebRTC thread and Flutter main thread during connection setup.

---

### 3. DataChannelEventDispatcher.java (NEW FILE)

**Path:** `android/src/main/java/com/cloudwebrtc/webrtc/DataChannelEventDispatcher.java`

**Purpose:** Background thread + batched event delivery to Flutter.

**Key Features:**
- Dedicated `HandlerThread` for message preparation (off main thread)
- Batches multiple messages into single `eventSink.success()` call
- Object pooling for `HashMap` instances to reduce GC pressure
- Configurable batch interval (default: 16ms)

**Architecture:**
```
WebRTC Thread                    Background Thread               Main Thread
─────────────                    ─────────────────               ───────────
onMessage() ──────────────────→ dispatchMessage()
                                   ↓
                                 prepareEvent()
                                 addToBatch()
                                   ↓
                                 [batch interval]
                                   ↓
                                                    ──────────→ eventSink.success(batch)
                                                                   ↓
                                                               Dart receives List<Map>
```

**Why:** Reduces platform channel crossings from N (one per message) to 1 (single batch), dramatically reducing main thread contention.

---

### 4. rtc_data_channel_impl.dart

**Path:** `lib/src/native/rtc_data_channel_impl.dart`

**Change:** Added handler for new `dataChannelBatchReceive` event type.

**Before:**
```dart
case 'dataChannelReceiveMessage':
    // Handle single message
```

**After:**
```dart
case 'dataChannelBatchReceive':
    final messages = map['messages'] as List<dynamic>;
    for (final msgMap in messages) {
        // Process each message in batch
    }
    break;
case 'dataChannelReceiveMessage':
    // Handle single message (backward compatible)
```

**Why:** Dart side needs to unpack batch events sent by `DataChannelEventDispatcher`.

---

### 5. PeerConnectionObserver.java (Data Channel Send Fix)

**Path:** `android/src/main/java/com/cloudwebrtc/webrtc/PeerConnectionObserver.java`

**Problem:** The native `DataChannel.send()` returns a boolean indicating success/failure, but the return value was completely ignored. This caused silent RPC failures where the client logged "sent" but the message never reached the server.

**Before:**
```java
void dataChannelSend(String dataChannelId, ByteBuffer byteBuffer, Boolean isBinary) {
    DataChannel dataChannel = dataChannels.get(dataChannelId);
    if (dataChannel != null) {
        DataChannel.Buffer buffer = new DataChannel.Buffer(byteBuffer, isBinary);
        dataChannel.send(buffer);  // RETURN VALUE IGNORED!
    } else {
        Log.d(TAG, "dataChannelSend() dataChannel is null");
    }
}
```

**After:**
```java
boolean dataChannelSend(String dataChannelId, ByteBuffer byteBuffer, Boolean isBinary) {
    DataChannel dataChannel = dataChannels.get(dataChannelId);
    if (dataChannel == null) {
        Log.w(TAG, "dataChannelSend() dataChannel is null for id: " + dataChannelId);
        return false;
    }

    DataChannel.State state = dataChannel.state();
    if (state != DataChannel.State.OPEN) {
        Log.w(TAG, "dataChannelSend() channel not open, state: " + state);
        return false;
    }

    // Retry loop with exponential backoff (10ms, 20ms, 40ms)
    int retryCount = 0;
    int delayMs = 10;  // INITIAL_RETRY_DELAY_MS

    while (retryCount <= 3) {  // MAX_SEND_RETRIES
        byteBuffer.rewind();
        DataChannel.Buffer buffer = new DataChannel.Buffer(byteBuffer, isBinary);

        boolean success = dataChannel.send(buffer);
        if (success) {
            if (retryCount > 0) {
                Log.d(TAG, "dataChannelSend() succeeded after " + retryCount + " retries");
            }
            return true;
        }

        if (retryCount >= 3) {
            Log.w(TAG, "dataChannelSend() failed after 3 retries");
            return false;
        }

        Thread.sleep(delayMs);
        retryCount++;
        delayMs *= 2;  // Exponential backoff
    }
    return false;
}
```

**Why:**
1. **Check return value**: `DataChannel.send()` returns `false` when buffer is full or channel state is bad
2. **Retry with backoff**: Transient buffer issues often resolve within milliseconds
3. **State verification**: Check channel is OPEN before attempting send
4. **Logging**: Debug logs help identify send failures in production

---

### 6. MethodCallHandlerImpl.java (Data Channel Send Fix)

**Path:** `android/src/main/java/com/cloudwebrtc/webrtc/MethodCallHandlerImpl.java`

**Changes:**
1. Case handler now returns `{"success": true/false}` instead of `null`
2. Helper method returns `boolean` instead of `void`

**Before:**
```java
case "dataChannelSend": {
    // ... argument parsing ...
    dataChannelSend(peerConnectionId, dataChannelId, byteBuffer, isBinary);
    result.success(null);  // Always returns null
    break;
}
```

**After:**
```java
case "dataChannelSend": {
    // ... argument parsing ...
    boolean sendSuccess = dataChannelSend(peerConnectionId, dataChannelId, byteBuffer, isBinary);
    ConstraintsMap params = new ConstraintsMap();
    params.putBoolean("success", sendSuccess);
    result.success(params.toMap());  // Returns {"success": true/false}
    break;
}
```

**Why:** Allows Dart layer to detect send failures and implement higher-level retry logic if needed.

---

## Test Results

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Avg RTT | 14,303ms | 1,177ms | **-92%** |
| Min RTT | 5,286ms | 131ms | **-97.5%** |
| Max RTT | 20,382ms | 3,287ms | **-84%** |
| P95 RTT | 19,626ms | 2,552ms | **-87%** |

See [EXPLORATION.md](../../EXPLORATION.md) in the root project for detailed test methodology and results.

---

## Upstream Contribution

These changes are candidates for upstream contribution to:
- https://github.com/flutter-webrtc/flutter-webrtc

The modifications are backward-compatible and provide significant benefits for low-end Android devices.

---

## Reverting Changes

To revert to stock `flutter_webrtc`:

1. Remove the `dependency_overrides` section from `pubspec.yaml`
2. Or replace this package with the official pub.dev version:
   ```yaml
   dependencies:
     flutter_webrtc: ^1.3.0
   ```
