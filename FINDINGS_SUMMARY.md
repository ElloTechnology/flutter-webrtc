# WebRTC Data Channel Latency on Low-End Android: Findings & Fixes

## Problem

On low-end Android devices (e.g., Infinix X6532C), WebRTC data channel round-trip time degrades from ~5s to ~20s within 30 seconds when microphone streaming is active. The issue does **not** reproduce on iOS, Chrome/Web, or Android with mic disabled. Dart-side processing was confirmed fast (<1ms avg), pointing to bottlenecks in the Android native layer of `flutter_webrtc`.

## Root Causes Identified (Android SDK)

1. **Lock contention in `LocalAudioTrack.java`**: `synchronized(sinks)` blocks on the audio callback hot path cause contention between the WebRTC audio thread and the main thread — even when the sinks list is empty.

2. **Thread-unsafe queue in `DataChannelObserver.java`**: An unsynchronized `ArrayList` used as an event queue between the WebRTC thread and the Flutter main thread causes race conditions during connection setup.

3. **Platform channel saturation**: Each incoming data channel message triggers a separate `eventSink.success()` call, meaning N messages = N platform channel crossings. On CPU-constrained devices, this saturates the main looper.

4. **Ignored `DataChannel.send()` return value**: The native send call's boolean result was discarded, causing silent message failures with no retry mechanism.

## Fixes Applied (to `flutter_webrtc` v1.3.0)

| Fix | Change | RTT Impact |
|-----|--------|------------|
| `CopyOnWriteArrayList` in `LocalAudioTrack.java` | Remove synchronized blocks on audio sink iteration | -35% avg RTT |
| `CopyOnWriteArrayList` in `DataChannelObserver.java` | Thread-safe event queue + eliminate race condition | -30% avg RTT |
| `DataChannelEventDispatcher.java` (new) | Background HandlerThread + batch delivery: N messages → 1 `eventSink.success()` call with object pooling | -82% avg RTT |
| `PeerConnectionObserver.java` send fix | Check `send()` return value, retry with exponential backoff, state verification | RPC reliability |

## Results (Infinix X6532C, Ultra Low-End Simulation, Mic Enabled)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Avg RTT | 14,303ms | 1,177ms | **-92%** |
| Min RTT | 5,286ms | 131ms | **-97.5%** |
| P95 RTT | 19,626ms | 2,552ms | **-87%** |
| Max RTT | 20,382ms | 3,287ms | **-84%** |
| RTT Trend | Linear degradation | Recovery to sub-second | Stable |

The most impactful single fix was **batching platform channel events** (-82%). Reducing N `eventSink.success()` calls to 1 batched call amortizes the ~50-200μs per-crossing overhead on low-end devices.

## Trade-off

FPS dropped from 3.4 to 1.9 with batching enabled (batch processing briefly blocks the main isolate) also is more overhead on thread synchronization. For real-time communication, sub-second data channel latency is the higher priority. Alas this comes with some increased memory pressure.

## Recommendation

All changes are backward-compatible and candidates for upstream contribution to [flutter-webrtc/flutter-webrtc](https://github.com/flutter-webrtc/flutter-webrtc). The `CopyOnWriteArrayList` and batched event delivery fixes would benefit any app using data channels on low-end Android devices.
