package com.maxim.quiz.data;

import android.content.Context;

import androidx.annotation.IntDef;
import androidx.annotation.StringRes;

import com.maxim.quiz.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class DifficultyLevel {

    public static final int EASY = 0;
    public static final int MEDIUM = 1;
    public static final int HARD = 2;

    @IntDef({EASY, MEDIUM, HARD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Value {
    }

    private DifficultyLevel() {
    }

    @Value
    public static int fromLegacyMode(String mode) {
        if ("basic".equalsIgnoreCase(mode)) {
            return EASY;
        }
        if ("advanced".equalsIgnoreCase(mode)) {
            return HARD;
        }
        return MEDIUM;
    }

    public static String toLegacyMode(@Value int level) {
        if (level == EASY) {
            return "basic";
        }
        if (level == HARD) {
            return "advanced";
        }
        return "common";
    }

    @StringRes
    public static int toLabelRes(@Value int level) {
        if (level == EASY) {
            return R.string.difficulty_basic;
        }
        if (level == HARD) {
            return R.string.difficulty_advanced;
        }
        return R.string.difficulty_common;
    }

    public static String toLabel(Context context, @Value int level) {
        return context.getString(toLabelRes(level));
    }
}
