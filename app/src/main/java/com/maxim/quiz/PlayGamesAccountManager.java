package com.maxim.quiz;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.games.GamesSignInClient;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.PlayGamesSdk;
import com.maxim.quiz.data.QuizRepository;
import com.maxim.quiz.data.remote.dto.QuizApiModels;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates Play Games v2 authentication and secure server-side linking. */
public final class PlayGamesAccountManager {

    private static final String TAG = "PlayGamesAccount";
    private static final String PREF_LINKED = "pref_play_games_linked";
    private static final String PREF_SIGNED_OUT = "pref_play_games_signed_out";
    private static final AtomicBoolean LINK_IN_PROGRESS = new AtomicBoolean(false);

    public interface Callback {
        void onComplete(boolean success, Throwable error);
    }

    private PlayGamesAccountManager() {
    }

    public static void initialize(Context context) {
        if (!hasGameProjectId()) {
            Log.w(TAG, "Play Games is disabled: PGS_GAME_PROJECT_ID is not configured");
            return;
        }
        PlayGamesSdk.initialize(context.getApplicationContext());
    }

    public static boolean isConfigured() {
        return hasGameProjectId() && !BuildConfig.PGS_SERVER_CLIENT_ID.trim().isEmpty();
    }

    public static boolean isLinked(Context context) {
        return preferences(context).getBoolean(PREF_LINKED, false);
    }

    public static String getStatusSummary(Context context) {
        if (!isConfigured()) {
            return context.getString(R.string.settings_google_play_not_configured);
        }
        if (isLinked(context)) {
            return context.getString(R.string.settings_google_play_connected);
        }
        return context.getString(R.string.settings_google_play_not_connected);
    }

    /** Attempts the silent platform sign-in after the normal app bootstrap. */
    public static void attemptAutomaticLink(Activity activity) {
        if (!isConfigured() || !NetworkState.isAvailable(activity)) {
            return;
        }
        SharedPreferences preferences = preferences(activity);
        if (preferences.getBoolean(PREF_LINKED, false)
                || preferences.getBoolean(PREF_SIGNED_OUT, false)
                || !LINK_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }

        GamesSignInClient client = PlayGames.getGamesSignInClient(activity);
        client.isAuthenticated().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || !task.getResult().isAuthenticated()) {
                LINK_IN_PROGRESS.set(false);
                Log.i(TAG, "Automatic Play Games sign-in is not available");
                return;
            }
            requestServerAuthCode(activity, client, null);
        });
    }

    /** Starts interactive sign-in from Settings when automatic sign-in failed or is disabled. */
    public static void signInAndLink(Activity activity, Callback callback) {
        if (!isConfigured()) {
            notifyCallback(activity, callback, false,
                    new IllegalStateException("Play Games credentials are not configured"));
            return;
        }
        if (!LINK_IN_PROGRESS.compareAndSet(false, true)) {
            notifyCallback(activity, callback, false,
                    new IllegalStateException("Play Games sign-in is already in progress"));
            return;
        }

        preferences(activity).edit().putBoolean(PREF_SIGNED_OUT, false).apply();
        GamesSignInClient client = PlayGames.getGamesSignInClient(activity);
        client.signIn().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || !task.getResult().isAuthenticated()) {
                LINK_IN_PROGRESS.set(false);
                notifyCallback(activity, callback, false,
                        task.getException() != null
                                ? task.getException()
                                : new IllegalStateException("Play Games sign-in was not completed"));
                return;
            }
            requestServerAuthCode(activity, client, callback);
        });
    }

    /** Logs out of the Quiz account locally. Play Games account selection is managed by Android. */
    public static void signOutFromQuiz(Context context) {
        preferences(context).edit()
                .putBoolean(PREF_LINKED, false)
                .putBoolean(PREF_SIGNED_OUT, true)
                .apply();
        QuizRepository.create(context).clearLocalAccountSession();
    }

    private static void requestServerAuthCode(Activity activity, GamesSignInClient client, Callback callback) {
        client.requestServerSideAccess(BuildConfig.PGS_SERVER_CLIENT_ID, false)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().trim().isEmpty()) {
                        LINK_IN_PROGRESS.set(false);
                        notifyCallback(activity, callback, false,
                                task.getException() != null
                                        ? task.getException()
                                        : new IllegalStateException("Play Games server authorization failed"));
                        return;
                    }
                    linkWithServer(activity, task.getResult(), callback);
                });
    }

    private static void linkWithServer(Activity activity, String serverAuthCode, Callback callback) {
        new Thread(() -> {
            try {
                QuizRepository repository = QuizRepository.create(activity.getApplicationContext());
                repository.linkPlayGamesAccountBlocking(serverAuthCode);
                repository.syncBootstrapBlocking(QuizLanguage.current(activity));
                preferences(activity).edit()
                        .putBoolean(PREF_LINKED, true)
                        .putBoolean(PREF_SIGNED_OUT, false)
                        .apply();
                notifyCallback(activity, callback, true, null);
            } catch (Throwable error) {
                Log.e(TAG, "Could not link Play Games account", error);
                notifyCallback(activity, callback, false, error);
            } finally {
                LINK_IN_PROGRESS.set(false);
            }
        }, "quiz-play-games-link").start();
    }

    private static void notifyCallback(Activity activity, Callback callback, boolean success, Throwable error) {
        if (callback == null) {
            return;
        }
        activity.runOnUiThread(() -> callback.onComplete(success, error));
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    private static boolean hasGameProjectId() {
        return BuildConfig.PGS_GAME_PROJECT_ID != null
                && !BuildConfig.PGS_GAME_PROJECT_ID.trim().isEmpty()
                && !"0".equals(BuildConfig.PGS_GAME_PROJECT_ID.trim());
    }
}
