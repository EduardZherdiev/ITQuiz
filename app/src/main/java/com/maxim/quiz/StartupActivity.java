package com.maxim.quiz;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.maxim.quiz.data.QuizRepository;
import com.maxim.quiz.data.QuizRepository.BootstrapFlowCallback;
import com.maxim.quiz.data.QuizRepository.BootstrapStage;

import java.util.concurrent.Semaphore;

public class StartupActivity extends AppCompatActivity {

    private static final String KEY_STARTUP_COMPLETED = "pref_startup_completed";
    private static final long PROGRESS_TICK_MS = 90L;
    private static final long TEST_STEP_DELAY_MS = 1200L;
    private static final String TAG = "StartupActivity";

    private enum Stage {
        INTRO,
        BOOTSTRAP,
        READY
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (finished || progressValue >= 95) {
                return;
            }
            progressValue = Math.min(95, progressValue + 2);
            updateProgressUi();
            handler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    private ProgressBar progressBar;
    private TextView progressText;
    private TextView statusText;
    private TextView loadingText;
    private MaterialButton nextButton;
    private View errorGroup;
    private MaterialButton retryButton;
    private QuizRepository repository;
    private String language;
    private int progressValue;
    private boolean finished;
    private Stage currentStage = Stage.INTRO;
    private boolean bootstrapFinished;
    private final Semaphore nextStepGate = new Semaphore(0);
    private BootstrapFlowCallback activeFlowCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Do not render the startup screen again after the first successful
        // bootstrap. This prevents a light/dark flash when the process is
        // recreated after changing the application language.
        if (isStartupComplete()) {
            goToMain();
            return;
        }
        setContentView(R.layout.activity_startup);

        progressBar = findViewById(R.id.startupProgressBar);
        progressText = findViewById(R.id.startupProgressText);
        statusText = findViewById(R.id.startupStatusText);
        loadingText = findViewById(R.id.startupLoadingText);
        nextButton = findViewById(R.id.startupNextButton);
        errorGroup = findViewById(R.id.startupErrorGroup);
        retryButton = findViewById(R.id.startupRetryButton);
        HeroImageLoader.load(findViewById(R.id.startupHeroImage));

        repository = QuizRepository.create(this);
        language = QuizLanguage.current(this);

        retryButton.setOnClickListener(v -> startBootstrapFlow());
        nextButton.setOnClickListener(v -> advanceStage());

        startTestFlow();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startBootstrapFlow() {
        if (finished) {
            return;
        }
        if (!NetworkState.isAvailable(this)) {
            showError(R.string.startup_network_required);
            return;
        }

        bootstrapFinished = false;
        currentStage = Stage.BOOTSTRAP;
        errorGroup.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        nextButton.setEnabled(false);
        statusText.setText(R.string.startup_stage_connecting);
        loadingText.setText(getString(R.string.startup_stage_bootstrap, describeLanguage(language)));
        Log.d(TAG, "startBootstrapFlow: language=" + language + ", network=" + NetworkState.isAvailable(this));
        progressValue = 0;
        updateProgressUi();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);

        activeFlowCallback = new BootstrapFlowCallback() {
            @Override
            public void onStageChanged(BootstrapStage stage, String message) {
                runOnUiThread(() -> handleBootstrapStage(stage));
            }

            @Override
            public void awaitNext() throws InterruptedException {
                if (finished) {
                    return;
                }
                nextStepGate.acquire();
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> completeStartup());
            }

            @Override
            public void onError(Throwable throwable) {
                runOnUiThread(() -> showError(R.string.startup_server_unavailable));
            }
        };

        repository.syncBootstrapAsync(language, activeFlowCallback);
    }

    private void completeStartup() {
        if (finished) {
            return;
        }
        handler.removeCallbacks(progressTick);
        progressValue = 100;
        updateProgressUi();
        bootstrapFinished = true;
        currentStage = Stage.READY;
        statusText.setText(R.string.startup_stage_ready);
        loadingText.setText(R.string.startup_stage_ready);
        nextButton.setText(R.string.startup_next);
        nextButton.setVisibility(View.VISIBLE);
        nextButton.setEnabled(true);
        Log.d(TAG, "completeStartup: bootstrap finished for language=" + language);
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(KEY_STARTUP_COMPLETED, true)
                .apply();
    }

    private void showError(int messageRes) {
        handler.removeCallbacks(progressTick);
        progressValue = 0;
        updateProgressUi();
        statusText.setText(messageRes);
        loadingText.setText(R.string.startup_loading_data);
        nextButton.setVisibility(View.GONE);
        nextButton.setEnabled(false);
        currentStage = Stage.INTRO;
        bootstrapFinished = false;
        Log.d(TAG, "showError: messageRes=" + messageRes);
        errorGroup.setVisibility(View.VISIBLE);
    }

    private void startTestFlow() {
        currentStage = Stage.INTRO;
        bootstrapFinished = false;
        errorGroup.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        nextButton.setEnabled(false);
        statusText.setText(getString(R.string.startup_stage_language_ready, describeLanguage(language)));
        loadingText.setText(getString(R.string.startup_stage_language_ready, describeLanguage(language)));
        progressValue = 0;
        updateProgressUi();
        Log.d(TAG, "startTestFlow: language=" + language);
        handler.postDelayed(() -> {
            if (finished) {
                return;
            }
            nextButton.setVisibility(View.VISIBLE);
            nextButton.setEnabled(true);
            nextButton.setText(R.string.startup_next);
            Log.d(TAG, "startTestFlow: next enabled");
        }, TEST_STEP_DELAY_MS);
    }

    private void advanceStage() {
        if (finished) {
            return;
        }
        Log.d(TAG, "advanceStage: currentStage=" + currentStage + ", bootstrapFinished=" + bootstrapFinished);
        if (currentStage == Stage.INTRO) {
            startBootstrapFlow();
            return;
        }
        if (currentStage == Stage.READY && bootstrapFinished) {
            goToMain();
            return;
        }
        nextStepGate.release();
        nextButton.setEnabled(false);
    }

    private void handleBootstrapStage(BootstrapStage stage) {
        if (finished) {
            return;
        }
        currentStage = Stage.BOOTSTRAP;
        nextButton.setVisibility(View.VISIBLE);
        nextButton.setEnabled(true);
        nextButton.setText(R.string.startup_next);

        if (stage == BootstrapStage.CONNECTING) {
            statusText.setText(R.string.startup_stage_connecting);
            loadingText.setText(getString(R.string.startup_stage_bootstrap, describeLanguage(language)));
        } else if (stage == BootstrapStage.TOPICS_READY) {
            statusText.setText(R.string.startup_stage_topics_ready);
            loadingText.setText(R.string.startup_stage_topics_ready);
        } else if (stage == BootstrapStage.ASSETS_READY) {
            statusText.setText(R.string.startup_stage_assets_ready);
            loadingText.setText(R.string.startup_stage_assets_ready);
        } else if (stage == BootstrapStage.QUESTIONS_READY) {
            statusText.setText(R.string.startup_stage_questions_ready);
            loadingText.setText(R.string.startup_stage_questions_ready);
        }
        Log.d(TAG, "handleBootstrapStage: stage=" + stage);
    }

    private void updateProgressUi() {
        progressBar.setProgress(progressValue);
        progressText.setText(getString(R.string.startup_progress_percent, progressValue));
    }

    private String describeLanguage(String lang) {
        if ("ru".equalsIgnoreCase(lang)) {
            return "Русский";
        }
        if ("uk".equalsIgnoreCase(lang)) {
            return "Українська";
        }
        return "English";
    }

    private boolean isStartupComplete() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return preferences.getBoolean(KEY_STARTUP_COMPLETED, false);
    }

    private void goToMain() {
        if (finished) {
            return;
        }
        finished = true;
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
