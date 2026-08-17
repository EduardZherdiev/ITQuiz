package com.maxim.quiz;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import com.maxim.quiz.data.QuizRepository;


public class QuizApplication extends Application {

    private static final String KEY_THEME = "pref_theme";
    private static final String KEY_CURRENCY_BALANCE = "pref_currency_balance";
    private static final String PREF_USER_ID = "pref_user_id";
    private static final String PREF_STARTUP_COMPLETED = "pref_startup_completed";
    private static final int DEFAULT_CURRENCY_BALANCE = 3500;
    private static final String TAG = "QuizApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        NetworkState.initialize(this);
        applySavedLanguage();
        ensureInitialCurrencyBalance();
        android.util.Log.i(TAG, "onCreate: language=" + QuizLanguage.current(this)
                + ", networkAvailable=" + NetworkState.isAvailable()
                + ", displayedBalance=" + getCurrencyBalance(this));
        NetworkState.addListener(available -> {
            if (available) {
                scheduleNetworkSync(true);
            }
        });
        // Remove only unsupported stale translations even if the connection is
        // already off. Previously downloaded supported languages are kept.
        scheduleNetworkSync(PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PREF_STARTUP_COMPLETED, false));
        applySavedTheme();
    }

    private void scheduleNetworkSync(boolean refreshBootstrap) {
        Context appContext = getApplicationContext();
        new Thread(() -> {
            QuizRepository repository = QuizRepository.create(appContext);
            repository.pruneCachedLanguages(QuizLanguage.current(appContext));
            android.util.Log.i(TAG, "network-sync: started, refreshBootstrap=" + refreshBootstrap
                    + ", networkAvailable=" + NetworkState.isAvailable()
                    + ", localBalance=" + getCurrencyBalance(appContext));
            if (!NetworkState.isAvailable()) {
                android.util.Log.i(TAG, "network-sync: skipped because network is unavailable");
                return;
            }
            try {
                // Keep the catalog refresh after both outboxes. Otherwise a
                // failed pending purchase could be hidden by a bootstrap that
                // overwrites the optimistic local ownership and balance.
                repository.syncOfflineSessionsBlocking();
                repository.syncPendingAssetOperationsBlocking();
                if (refreshBootstrap) {
                    repository.syncBootstrapAsync(QuizLanguage.current(appContext));
                }
                android.util.Log.i(TAG, "network-sync: outboxes flushed, localBalance="
                        + getCurrencyBalance(appContext));
            } catch (Exception error) {
                android.util.Log.e(TAG, "network-sync: postponed, localBalance="
                        + getCurrencyBalance(appContext), error);
            }
        }, "quiz-startup-sync").start();
    }

    public static int getCurrencyBalance(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(KEY_CURRENCY_BALANCE, DEFAULT_CURRENCY_BALANCE);
    }

    public static void setDisplayedCurrencyBalance(Context context, int amount) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(KEY_CURRENCY_BALANCE, Math.max(0, amount)).apply();
    }

    private void applySavedLanguage() {
        String selected = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("pref_language", "");
        if (!"en".equals(selected) && !"ru".equals(selected) && !"uk".equals(selected)) {
            return;
        }
        String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        String deviceLanguage = java.util.Locale.getDefault().getLanguage();
        if (selected.equals(current)) {
            return;
        }
        if ((current == null || current.isEmpty()) && selected.equals(deviceLanguage)) {
            return;
        }
        if (!selected.equals(current)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected));
        }
    }

    private void ensureInitialCurrencyBalance() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.contains(KEY_CURRENCY_BALANCE)) {
            preferences.edit().putInt(KEY_CURRENCY_BALANCE, DEFAULT_CURRENCY_BALANCE).apply();
        }
    }

    private void applySavedTheme() {
        String themeValue = PreferenceManager.getDefaultSharedPreferences(this).getString(KEY_THEME, "system");
        if ("light".equals(themeValue)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if ("dark".equals(themeValue)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }
}
