package com.maxim.quiz;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.maxim.quiz.data.local.QuizDatabase;
import com.maxim.quiz.data.QuizRepository;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private ImageView settingsProfilePreview;
    private View settingsProfileFrame;
    private ImageView settingsProfileCrown;
    private TextView settingsProfileNameOverlay;
    private View languageLoadingOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // AppCompat recreates this Activity when the locale changes. Keep the
        // old and new window on the same opaque surface and remove the system
        // enter/exit transition that otherwise exposes a black frame.
        getWindow().setWindowAnimations(0);
        getWindow().setBackgroundDrawable(new ColorDrawable(resolveSurfaceColor()));
        overridePendingTransition(0, 0);
        setContentView(R.layout.settings_activity);
        ActivityTransitionBuffer.install(this);
        setTitle(R.string.title_activity_settings);
        settingsProfilePreview = findViewById(R.id.settingsProfilePreview);
        settingsProfileFrame = findViewById(R.id.settingsProfileFrame);
        settingsProfileCrown = findViewById(R.id.settingsProfileCrown);
        settingsProfileNameOverlay = findViewById(R.id.settingsProfileNameOverlay);
        languageLoadingOverlay = findViewById(R.id.languageLoadingOverlay);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        refreshProfilePreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfilePreview();
    }

    public void refreshProfilePreview() {
        if (settingsProfilePreview == null || settingsProfileFrame == null || settingsProfileCrown == null) {
            return;
        }
        AvatarAssetsHelper.applyUserAvatar(this, settingsProfileFrame, settingsProfilePreview, settingsProfileCrown, R.drawable.user);
        AvatarAssetsHelper.applyUserDisplayName(this, settingsProfileNameOverlay);
    }

    private int resolveSurfaceColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)) {
            return value.data;
        }
        return Color.TRANSPARENT;
    }

    public void showLanguageLoading() {
        if (languageLoadingOverlay != null) {
            languageLoadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLanguageLoading() {
        if (languageLoadingOverlay != null) {
            languageLoadingOverlay.setVisibility(View.GONE);
        }
    }

    /** Captures the already rendered screen without blocking the UI thread. */
    public void captureConfigurationTransitionFrame(Runnable afterCapture) {
        ActivityTransitionBuffer.captureAsync(this, afterCapture);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {
        private static final String KEY_THEME = "pref_theme";
        private static final String KEY_LANGUAGE = "pref_language";
        private static final String KEY_PROFILE_PHOTO = "pref_profile_photo";
        private static final String KEY_DISPLAY_NAME = "pref_user_display_name";
        private static final String PREF_PROFILE_PHOTO_URI = "pref_profile_photo_uri";
        private static final String PROFILE_PHOTO_FILE = "profile_photo.png";

        private ActivityResultLauncher<String[]> galleryPicker;
        private ActivityResultLauncher<Void> cameraPreviewLauncher;
        private final Handler configurationChangeHandler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean languageChangeInProgress = new AtomicBoolean(false);
        private Runnable pendingConfigurationChange;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            ListPreference themePreference = findPreference(KEY_THEME);
            ListPreference languagePreference = findPreference(KEY_LANGUAGE);
            Preference profilePhotoPreference = findPreference(KEY_PROFILE_PHOTO);
            EditTextPreference displayNamePreference = findPreference(KEY_DISPLAY_NAME);

            if (themePreference != null) {
                themePreference.setOnPreferenceChangeListener(this);
            }
            if (languagePreference != null) {
                languagePreference.setOnPreferenceChangeListener(this);
                initializeLanguagePreference(languagePreference);
            }
            if (profilePhotoPreference != null) {
                profilePhotoPreference.setOnPreferenceClickListener(preference -> {
                    showProfilePhotoChooser();
                    return true;
                });
            }
            if (displayNamePreference != null) {
                initializeDisplayNamePreference(displayNamePreference);
                displayNamePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    String displayName = String.valueOf(newValue == null ? "" : newValue).trim();
                    if (displayName.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.settings_user_name_empty_error, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    ((EditTextPreference) preference).setText(displayName);
                    preference.setSummary(displayName);
                    saveDisplayNameToDatabase(displayName);
                    if (getActivity() instanceof SettingsActivity) {
                        ((SettingsActivity) getActivity()).refreshProfilePreview();
                    }
                    return false;
                });
            }

            galleryPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                requireContext().getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString(PREF_PROFILE_PHOTO_URI, uri.toString())
                        .apply();
                if (getActivity() instanceof SettingsActivity) {
                    ((SettingsActivity) getActivity()).refreshProfilePreview();
                }
                Toast.makeText(requireContext(), R.string.settings_profile_photo_saved, Toast.LENGTH_SHORT).show();
            });

            cameraPreviewLauncher = registerForActivityResult(new ActivityResultContracts.TakePicturePreview(), bitmap -> {
                if (bitmap == null) {
                    return;
                }
                saveBitmapToPrivateFile(bitmap);
                if (getActivity() instanceof SettingsActivity) {
                    ((SettingsActivity) getActivity()).refreshProfilePreview();
                }
                Toast.makeText(requireContext(), R.string.settings_profile_photo_saved, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String value = String.valueOf(newValue);

            if (KEY_THEME.equals(preference.getKey())) {
                applyTheme(value);
                return true;
            }

            if (KEY_LANGUAGE.equals(preference.getKey())) {
                return requestLanguageChange(value);
            }

            return false;
        }

        private void showProfilePhotoChooser() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_profile_photo_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.settings_photo_camera),
                            getString(R.string.settings_photo_gallery),
                            getString(R.string.settings_photo_remove)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            cameraPreviewLauncher.launch(null);
                        } else if (which == 1) {
                            galleryPicker.launch(new String[]{"image/*"});
                        } else {
                            removeProfilePhoto();
                        }
                    })
                    .show();
        }

        private void removeProfilePhoto() {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .remove(PREF_PROFILE_PHOTO_URI)
                    .apply();

            File outputFile = new File(requireContext().getFilesDir(), PROFILE_PHOTO_FILE);
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }

            if (getActivity() instanceof SettingsActivity) {
                ((SettingsActivity) getActivity()).refreshProfilePreview();
            }
            Toast.makeText(requireContext(), R.string.settings_profile_photo_removed, Toast.LENGTH_SHORT).show();
        }

        private void saveBitmapToPrivateFile(Bitmap bitmap) {
            File outputFile = new File(requireContext().getFilesDir(), PROFILE_PHOTO_FILE);
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString(PREF_PROFILE_PHOTO_URI, Uri.fromFile(outputFile).toString())
                        .apply();
            } catch (IOException exception) {
                Toast.makeText(requireContext(), R.string.settings_profile_photo_failed, Toast.LENGTH_SHORT).show();
            }
        }

        private void applyTheme(String value) {
            final int nightMode;
            if ("light".equals(value)) {
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if ("dark".equals(value)) {
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }

            if (getActivity() instanceof SettingsActivity) {
                SettingsActivity activity = (SettingsActivity) getActivity();
                activity.captureConfigurationTransitionFrame(() -> {
                    if (isAdded()) {
                        activity.showLanguageLoading();
                        scheduleConfigurationChange(() -> AppCompatDelegate.setDefaultNightMode(nightMode));
                    }
                });
            } else {
                scheduleConfigurationChange(() -> AppCompatDelegate.setDefaultNightMode(nightMode));
            }
        }

        private void scheduleConfigurationChange(Runnable change) {
            if (pendingConfigurationChange != null) {
                configurationChangeHandler.removeCallbacks(pendingConfigurationChange);
            }
            pendingConfigurationChange = () -> {
                if (isAdded()) {
                    change.run();
                }
            };
            configurationChangeHandler.postDelayed(pendingConfigurationChange, 180L);
        }

        private void applyLanguage(String value, boolean ignoredSyncBootstrap) {
            if (value == null || value.isEmpty() || "system".equals(value)) {
                return;
            }
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putString(KEY_LANGUAGE, value).apply();
            String currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            Log.d(TAG, "applyLanguage: requested=" + value + ", current=" + currentLocale);
            String deviceLanguage = Locale.getDefault().getLanguage();
            if (value.equals(currentLocale)) {
                return;
            }
            if ((currentLocale == null || currentLocale.isEmpty()) && value.equals(deviceLanguage)) {
                return;
            }
            if (!value.equals(currentLocale)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value));
            }
        }

        private boolean requestLanguageChange(String value) {
            if (value == null || value.isEmpty() || "system".equals(value)) {
                return false;
            }

            Context context = requireContext();
            Log.d(TAG, "requestLanguageChange: requested=" + value
                    + ", current=" + QuizLanguage.current(context)
                    + ", network=" + NetworkState.isAvailable(context));
            if (value.equals(QuizLanguage.current(context))) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString(KEY_LANGUAGE, value).apply();
                return false;
            }

            // English is always bundled and is also downloaded during the
            // first bootstrap, so it must remain selectable offline.
            if ("en".equals(value)) {
                commitLanguageAfterValidation(value, false);
                return false;
            }

            if (!languageChangeInProgress.compareAndSet(false, true)) {
                return false;
            }

            SettingsActivity hostActivity = getActivity() instanceof SettingsActivity
                    ? (SettingsActivity) getActivity() : null;
            if (hostActivity != null) {
                hostActivity.showLanguageLoading();
            }
            Context appContext = context.getApplicationContext();
            QuizRepository repository = QuizRepository.create(appContext);
            new Thread(() -> {
                Throwable failure = null;
                boolean cached = false;
                try {
                    // Room queries must never run from the preference callback
                    // on the main thread.
                    cached = repository.isLanguageCached(value);
                    Log.d(TAG, "requestLanguageChange: cached=" + cached + " language=" + value);
                    if (!cached) {
                        if (!NetworkState.isAvailable(appContext)) {
                            throw new java.io.IOException("Network is unavailable");
                        }
                        // A new language is accepted only after an authenticated
                        // server request successfully downloads it.
                        repository.syncBootstrapBlocking(value);
                    }
                } catch (Throwable error) {
                    failure = error;
                    Log.e(TAG, "Language download failed: " + value, error);
                }
                Throwable result = failure;
                boolean wasCached = cached;
                if (hostActivity == null) {
                    languageChangeInProgress.set(false);
                    return;
                }
                hostActivity.runOnUiThread(() -> {
                    languageChangeInProgress.set(false);
                    hostActivity.hideLanguageLoading();
                    if (result == null) {
                        commitLanguageAfterValidation(value, !wasCached);
                    } else {
                        boolean connectionError = !NetworkState.isAvailable(hostActivity)
                                || isConnectivityFailure(result);
                        Toast.makeText(hostActivity,
                                connectionError
                                        ? R.string.settings_language_requires_connection
                                        : R.string.settings_language_download_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }, "quiz-language-download").start();
            return false;
        }

        private boolean isConnectivityFailure(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof java.net.ConnectException
                        || current instanceof java.net.SocketTimeoutException
                        || current instanceof java.net.UnknownHostException
                        || current instanceof java.net.SocketException) {
                    return true;
                }
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.toLowerCase(Locale.ROOT);
                    if (normalized.contains("timeout")
                            || normalized.contains("network is unavailable")
                            || normalized.contains("failed to connect")
                            || normalized.contains("unable to resolve host")) {
                        return true;
                    }
                }
                current = current.getCause();
            }
            return false;
        }

        private void commitLanguageAfterValidation(String value, boolean downloaded) {
            if (!isAdded()) {
                return;
            }
            Context context = requireContext();
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putString(KEY_LANGUAGE, value).apply();
            ListPreference languagePreference = findPreference(KEY_LANGUAGE);
            if (languagePreference != null) {
                languagePreference.setValue(value);
            }

            String currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            if (value.equals(currentLocale)) {
                return;
            }
            if (getActivity() instanceof SettingsActivity) {
                SettingsActivity activity = (SettingsActivity) getActivity();
                activity.captureConfigurationTransitionFrame(() -> {
                    if (isAdded()) {
                        if (downloaded) {
                            activity.showLanguageLoading();
                        }
                        scheduleConfigurationChange(() ->
                                AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(value)
                                )
                        );
                    }
                });
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value));
            }
        }

        @Override
        public void onDestroyView() {
            if (pendingConfigurationChange != null) {
                configurationChangeHandler.removeCallbacks(pendingConfigurationChange);
                pendingConfigurationChange = null;
            }
            super.onDestroyView();
        }

        private void initializeLanguagePreference(ListPreference languagePreference) {
            String savedValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(KEY_LANGUAGE, null);
            if (savedValue == null || savedValue.isEmpty() || "system".equals(savedValue)) {
                String deviceLanguage = getSupportedDeviceLanguage();
                languagePreference.setValue(deviceLanguage);
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putString(KEY_LANGUAGE, deviceLanguage).apply();
                applyLanguage(deviceLanguage, false);
                return;
            }

            languagePreference.setValue(savedValue);
            applyLanguage(savedValue, false);
        }

        private String getSupportedDeviceLanguage() {
            String language = Locale.getDefault().getLanguage();
            if ("ru".equals(language) || "uk".equals(language) || "en".equals(language)) {
                return language;
            }
            return "en";
        }

        private void initializeDisplayNamePreference(EditTextPreference displayNamePreference) {
            String savedName = displayNamePreference.getText();
            if (savedName == null || savedName.trim().isEmpty()) {
                savedName = "Max Tester";
                displayNamePreference.setText(savedName);
            }
            displayNamePreference.setSummary(savedName);
        }

        private void saveDisplayNameToDatabase(String displayName) {
            String userId = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("pref_user_id", "user_test");
            new Thread(() -> QuizDatabase.getInstance(requireContext())
                    .quizDao()
                    .updateUserDisplayName(userId, displayName)).start();
        }
    }
}
