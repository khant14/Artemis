package com.limelight.utils;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.limelight.LimeLog;

// Some SoCs (e.g. MediaTek) only raise CPU/DRAM/decoder clocks while the touchscreen is in use.
// This periodically injects a synthetic tap into our own window to keep that boost active while
// streaming with a controller. The synthetic events are recognized by their down time and
// consumed in Game.dispatchTouchEvent() before reaching any view.
public class TouchBoostKeeper {
    private static final long INTERVAL_MS = 500;
    private static final long REAL_TOUCH_GRACE_MS = 500;

    private final Instrumentation instrumentation = new Instrumentation();
    private HandlerThread thread;
    private Handler handler;

    private volatile long syntheticDownTime = -1;
    private volatile boolean realTouchActive;
    private volatile long lastRealTouchTime;
    private int failures;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (!realTouchActive && now - lastRealTouchTime > REAL_TOUCH_GRACE_MS) {
                injectTap(now);
            }
            if (handler != null) {
                handler.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new HandlerThread("TouchBoostKeeper");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(tick, INTERVAL_MS);
        LimeLog.info("Touch boost keeper started");
    }

    public synchronized void stop() {
        if (thread == null) {
            return;
        }
        handler.removeCallbacksAndMessages(null);
        handler = null;
        thread.quitSafely();
        thread = null;
        LimeLog.info("Touch boost keeper stopped");
    }

    private void injectTap(long downTime) {
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_UP, 1, 1, 0);
        syntheticDownTime = downTime;
        try {
            instrumentation.sendPointerSync(down);
            instrumentation.sendPointerSync(up);
        } catch (Exception e) {
            // Injection fails when our window doesn't have focus (dialogs, PiP, backgrounded)
            if (failures++ == 0) {
                LimeLog.warning("Touch boost injection failed: " + e.getMessage());
            }
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    // Returns true if the event is one of our synthetic taps and should be consumed
    public boolean isSynthetic(MotionEvent event) {
        return event.getDownTime() == syntheticDownTime;
    }

    public void onRealTouch(MotionEvent event) {
        lastRealTouchTime = SystemClock.uptimeMillis();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE:
                realTouchActive = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                realTouchActive = false;
                break;
        }
    }
}
