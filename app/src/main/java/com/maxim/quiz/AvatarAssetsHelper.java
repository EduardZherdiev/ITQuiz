package com.maxim.quiz;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.io.InputStream;

public final class AvatarAssetsHelper {

    private static final String PREF_SELECTED_PREFIX = "pref_assets_selected_";
    private static final String PREF_PROFILE_PHOTO_URI = "pref_profile_photo_uri";
    private static final String PREF_USER_DISPLAY_NAME = "pref_user_display_name";

    private AvatarAssetsHelper() {
    }

    public static void applyUserAvatar(Context context, View frameView, ImageView imageView, ImageView crownView, int fallbackRes) {
        if (context == null || frameView == null || imageView == null) {
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String selectedFrameId = preferences.getString(PREF_SELECTED_PREFIX + "FRAME", "frame_classic");
        String selectedCrownId = preferences.getString(PREF_SELECTED_PREFIX + "CROWN", "crown_none");
        String profilePhotoUri = preferences.getString(PREF_PROFILE_PHOTO_URI, "");

        frameView.setBackgroundResource(getFrameBackgroundRes(selectedFrameId));
        applyProfileImage(context, imageView, profilePhotoUri, fallbackRes);
        applyCrown(crownView, selectedCrownId);
    }

    public static void applyDefaultAvatar(Context context, View frameView, ImageView imageView, int fallbackRes) {
        if (context == null || frameView == null || imageView == null) {
            return;
        }
        frameView.setBackgroundResource(R.drawable.bg_question_avatar_slot);
        imageView.setImageResource(fallbackRes);
        imageView.clearColorFilter();
    }

    public static void applyUserPhoto(Context context, ImageView imageView, int fallbackRes) {
        if (context == null || imageView == null) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String profilePhotoUri = preferences.getString(PREF_PROFILE_PHOTO_URI, "");
        applyProfileImage(context, imageView, profilePhotoUri, fallbackRes);
    }

    public static void applyUserDisplayName(Context context, TextView nameView) {
        if (context == null || nameView == null) {
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String displayName = preferences.getString(PREF_USER_DISPLAY_NAME, "");
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "Max Tester";
        }
        nameView.setText(displayName);
    }

    private static void applyProfileImage(Context context, ImageView imageView, String uriValue, int fallbackRes) {
        if (uriValue != null && !uriValue.trim().isEmpty()) {
            try {
                Uri photoUri = Uri.parse(uriValue);
                try (InputStream inputStream = context.getContentResolver().openInputStream(photoUri)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    if (bitmap != null) {
                        imageView.setImageBitmap(toRoundedSquareBitmap(context, bitmap));
                        imageView.clearColorFilter();
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Fallback to default icon.
            }
        }

        imageView.setImageResource(fallbackRes);
        imageView.clearColorFilter();
    }

    private static void applyCrown(ImageView crownView, String crownId) {
        if (crownView == null) {
            return;
        }

        int crownRes = getCrownDrawableRes(crownId);
        if (crownRes != 0) {
            crownView.setVisibility(View.VISIBLE);
            crownView.setImageResource(crownRes);
            crownView.clearColorFilter();
            crownView.bringToFront();
            return;
        }

        crownView.setVisibility(View.GONE);
    }

    private static int getCrownDrawableRes(String crownId) {
        if ("crown_white".equals(crownId)) {
            return R.drawable.silver_crown;
        }
        if ("crown_bronze".equals(crownId)) {
            return R.drawable.bronze_crown;
        }
        if ("crown_silver".equals(crownId)) {
            return R.drawable.silver_crown;
        }
        if ("crown_gold".equals(crownId)) {
            return R.drawable.gold_crown;
        }
        if ("crown_brilliant".equals(crownId)) {
            return R.drawable.brilliant_crown;
        }
        if ("crown_black".equals(crownId)) {
            return R.drawable.bronze_crown;
        }
        return 0;
    }

    private static int getFrameBackgroundRes(String frameId) {
        if ("frame_neon".equals(frameId)) {
            return R.drawable.bg_question_avatar_slot_neon;
        }
        if ("frame_royal".equals(frameId)) {
            return R.drawable.bg_question_avatar_slot_royal;
        }
        return R.drawable.bg_question_avatar_slot;
    }

    private static float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    private static Bitmap toRoundedSquareBitmap(Context context, Bitmap source) {
        int squareSize = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - squareSize) / 2;
        int top = (source.getHeight() - squareSize) / 2;

        Bitmap squareBitmap = Bitmap.createBitmap(squareSize, squareSize, Bitmap.Config.ARGB_8888);
        Canvas squareCanvas = new Canvas(squareBitmap);
        squareCanvas.drawBitmap(source, new Rect(left, top, left + squareSize, top + squareSize), new Rect(0, 0, squareSize, squareSize), null);

        Bitmap roundedBitmap = Bitmap.createBitmap(squareSize, squareSize, Bitmap.Config.ARGB_8888);
        Canvas roundedCanvas = new Canvas(roundedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = dpToPx(context, 12f);
        RectF rect = new RectF(0f, 0f, squareSize, squareSize);
        roundedCanvas.drawRoundRect(rect, radius, radius, paint);
        return roundedBitmap;
    }
}
