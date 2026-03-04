package com.cloudwebrtc.webrtc.utils;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Static utility to read the Android main-thread MessageQueue depth via reflection.
 */
public final class MainThreadQueueDepth {
    private static final String TAG = "MainThreadQueueDepth";
    private static final int MAX_WALK = 200;

    private static Field sQueueField;
    private static Field sMessagesField;
    private static Field sNextField;

    static {
        try {
            sQueueField = Looper.class.getDeclaredField("mQueue");
            sQueueField.setAccessible(true);
            sMessagesField = MessageQueue.class.getDeclaredField("mMessages");
            sMessagesField.setAccessible(true);
        } catch (Exception e) {
            Log.w(TAG, "Reflection init failed: " + e.getMessage());
            sQueueField = null;
            sMessagesField = null;
        }
    }

    private MainThreadQueueDepth() {}

    public static int get() {
        if (sQueueField == null || sMessagesField == null) return -1;
        try {
            MessageQueue queue = (MessageQueue) sQueueField.get(Looper.getMainLooper());
            if (queue == null) return -1;
            Object msg = sMessagesField.get(queue);
            if (msg == null) return 0;

            if (sNextField == null) {
                sNextField = msg.getClass().getDeclaredField("next");
                sNextField.setAccessible(true);
            }

            int count = 0;
            while (msg != null && count < MAX_WALK) {
                count++;
                msg = sNextField.get(msg);
            }
            return count;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read queue depth: " + e.getMessage());
            return -1;
        }
    }
}
