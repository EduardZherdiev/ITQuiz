package com.maxim.quiz;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Locale;

public final class QuizLanguage {
    private static final String PREF_LANGUAGE = "pref_language";

    private QuizLanguage() {
    }

    public static String current(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String selected = preferences.getString(PREF_LANGUAGE, "system");
        if (selected == null || selected.isEmpty() || "system".equals(selected)) {
            selected = Locale.getDefault().getLanguage();
        }
        if ("ru".equals(selected) || "uk".equals(selected)) {
            return selected;
        }
        return "en";
    }
}
