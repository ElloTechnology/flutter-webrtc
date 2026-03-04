package com.cloudwebrtc.webrtc.utils;

import android.os.Looper;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.flutter.plugin.common.MethodChannel;

public final class AnyThreadResult implements MethodChannel.Result {
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

    final private MethodChannel.Result result;
    final private Handler handler = new Handler(Looper.getMainLooper());

    public AnyThreadResult(MethodChannel.Result result) {
        this.result = result;
    }

    @Override
    public void success(Object o) {
        Trace.beginSection("FWRTC::AnyThreadResult::success");
        try {
            final String callingThread = Thread.currentThread().getName();
            post(() -> {
                Trace.beginSection("FWRTC::AnyThreadResult::success::mainThread");
                try {
                    Log.d(TAG, "AnyThreadResult::success delivered on thread=" + Thread.currentThread().getName() + " (from " + callingThread + ")");
                    result.success(o);
                } finally {
                    Trace.endSection();
                }
            });
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void error(String s, String s1, Object o) {
        Trace.beginSection("FWRTC::AnyThreadResult::error");
        try {
            post(() -> {
                Trace.beginSection("FWRTC::AnyThreadResult::error::mainThread");
                try {
                    result.error(s, s1, o);
                } finally {
                    Trace.endSection();
                }
            });
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void notImplemented() {
        Trace.beginSection("FWRTC::AnyThreadResult::notImplemented");
        try {
            post(result::notImplemented);
        } finally {
            Trace.endSection();
        }
    }

    private void post(Runnable r) {
        if(Looper.getMainLooper() == Looper.myLooper()){
            long t0 = System.nanoTime();
            r.run();
            long elapsed = System.nanoTime() - t0;
            dispatchedCount.incrementAndGet();
            totalDispatchNanos.addAndGet(elapsed);
        }else{
            Log.d(TAG, "AnyThreadResult::post from thread=" + Thread.currentThread().getName() + " -> posting to main thread");
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
