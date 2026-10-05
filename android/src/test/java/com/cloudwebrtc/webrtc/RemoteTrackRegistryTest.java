package com.cloudwebrtc.webrtc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteTrackRegistryTest {
  @Test
  public void keepsReplacementWhenPreviousTrackIsRemovedLate() {
    RemoteTrackRegistry<Object> registry = new RemoteTrackRegistry<>();
    Object previousTrack = new Object();
    Object replacementTrack = new Object();

    registry.put("video", previousTrack);
    registry.put("video", replacementTrack);

    assertFalse(registry.remove("video", previousTrack));
    assertSame(replacementTrack, registry.get("video"));
    assertTrue(registry.remove("video", replacementTrack));
  }

  @Test
  public void putIfAbsentKeepsExistingTrackAndFillsEmptySlot() {
    RemoteTrackRegistry<Object> registry = new RemoteTrackRegistry<>();
    Object first = new Object();
    Object second = new Object();

    registry.putIfAbsent("video", first);
    registry.putIfAbsent("video", second);

    assertSame(first, registry.get("video"));
  }
}
