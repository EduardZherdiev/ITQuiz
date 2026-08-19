package com.maxim.itquiz;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Keeps the last rendered Activity frame visible while AppCompat recreates a
 * window for a locale or night-mode change.
 *
 * Android already uses hardware double buffering for a normal window. This is
 * a short-lived visual back-buffer for the gap between two Activity windows;
 * it is deliberately not a SurfaceView/TextureView, which would add another
 * rendering pipeline and make Preference screens less stable.
 */
public final class ActivityTransitionBuffer {

    private static final Object LOCK = new Object();
    private static Bitmap pendingFrame;

    private ActivityTransitionBuffer() {
    }

    public static void captureAsync(Activity activity, Runnable afterCapture) {
        if (activity == null) {
            runCallback(afterCapture);
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) {
            runCallback(afterCapture);
            return;
        }

        Bitmap frame = Bitmap.createBitmap(
                content.getWidth(),
                content.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        int[] location = new int[2];
        content.getLocationInWindow(location);
        Rect source = new Rect(
                location[0],
                location[1],
                location[0] + content.getWidth(),
                location[1] + content.getHeight()
        );

        try {
            Window window = activity.getWindow();
            PixelCopy.request(window, source, frame, result -> {
                if (result == PixelCopy.SUCCESS) {
                    synchronized (LOCK) {
                        recyclePendingLocked();
                        pendingFrame = frame;
                    }
                } else if (!frame.isRecycled()) {
                    frame.recycle();
                }
                runCallback(afterCapture);
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException ignored) {
            if (!frame.isRecycled()) {
                frame.recycle();
            }
            runCallback(afterCapture);
        }
    }

    /**
     * Installs the captured frame above the new hierarchy. It is removed only
     * after the new hierarchy has completed two pre-draw passes, with a safe
     * timeout as a fallback if the window is stopped during recreation.
     */
    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }

        Bitmap frame;
        synchronized (LOCK) {
            frame = pendingFrame;
            pendingFrame = null;
        }
        if (frame == null || frame.isRecycled()) {
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            frame.recycle();
            return;
        }

        FrameLayout contentFrame = (FrameLayout) content;
        ImageView previousFrame = new ImageView(activity);
        previousFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previousFrame.setScaleType(ImageView.ScaleType.FIT_XY);
        previousFrame.setImageBitmap(frame);
        contentFrame.addView(previousFrame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        previousFrame.bringToFront();

        final int[] renderedFrames = {0};
        final ViewTreeObserver observer = contentFrame.getViewTreeObserver();
        final ViewTreeObserver.OnPreDrawListener[] listenerHolder = new ViewTreeObserver.OnPreDrawListener[1];
        listenerHolder[0] = () -> {
            renderedFrames[0]++;
            if (renderedFrames[0] >= 2) {
                removeFrame(contentFrame, previousFrame, frame, listenerHolder[0]);
            }
            return true;
        };
        observer.addOnPreDrawListener(listenerHolder[0]);

        // Never leave a large Bitmap attached if the Activity is stopped
        // before it gets a second frame.
        previousFrame.postDelayed(() -> removeFrame(
                contentFrame, previousFrame, frame, listenerHolder[0]), 900L);
    }

    private static void removeFrame(
            FrameLayout parent,
            ImageView frameView,
            Bitmap frame,
            ViewTreeObserver.OnPreDrawListener listener
    ) {
        if (listener != null && parent.getViewTreeObserver().isAlive()) {
            parent.getViewTreeObserver().removeOnPreDrawListener(listener);
        }
        if (frameView.getParent() == parent) {
            parent.removeView(frameView);
        }
        frameView.setImageDrawable(null);
        if (!frame.isRecycled()) {
            frame.recycle();
        }
    }

    private static void recyclePendingLocked() {
        if (pendingFrame != null && !pendingFrame.isRecycled()) {
            pendingFrame.recycle();
        }
        pendingFrame = null;
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }
}
