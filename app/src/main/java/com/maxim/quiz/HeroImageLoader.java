package com.maxim.quiz;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodes the large hero asset off the UI thread and at a display-sized resolution. */
public final class HeroImageLoader {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int MAX_DECODED_EDGE = 512;

    private HeroImageLoader() {
    }

    public static void load(ImageView target) {
        if (target == null) {
            return;
        }
        WeakReference<ImageView> targetReference = new WeakReference<>(target);
        // The bitmap has transparent pixels. Do not leave an opaque
        // placeholder surface behind it during asynchronous decoding.
        target.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        target.setImageDrawable(null);
        EXECUTOR.execute(() -> {
            ImageView view = targetReference.get();
            if (view == null) {
                return;
            }
            Bitmap bitmap = decode(view);
            if (bitmap == null) {
                return;
            }
            ImageView latestView = targetReference.get();
            if (latestView == null || !latestView.post(() -> {
                if (latestView.getWindowToken() == null) {
                    bitmap.recycle();
                    return;
                }
                latestView.setImageBitmap(bitmap);
            })) {
                bitmap.recycle();
            }
        });
    }

    private static Bitmap decode(ImageView view) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(view.getResources(), R.drawable.quiz_hero, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > MAX_DECODED_EDGE
                || bounds.outHeight / options.inSampleSize > MAX_DECODED_EDGE) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(view.getResources(), R.drawable.quiz_hero, options);
    }
}
