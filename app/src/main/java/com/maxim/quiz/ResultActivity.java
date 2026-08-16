package com.maxim.quiz;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.UUID;

import com.maxim.quiz.data.DifficultyLevel;
import com.maxim.quiz.data.local.QuizDatabase;
import com.maxim.quiz.data.local.entity.QuizSessionEntity;
import com.maxim.quiz.data.QuizRepository;
import com.maxim.quiz.data.remote.dto.QuizApiModels;

public class ResultActivity extends AppCompatActivity {

    private static final String EXTRA_SCORE = "extra_score";
    private static final String EXTRA_TOTAL = "extra_total";
    private static final String EXTRA_SESSION_ID = QuestionActivity.EXTRA_SESSION_ID;
    private static final String EXTRA_OPPONENT_SCORE = "opponent_score";
    private static final String EXTRA_REVIEW_LINES = QuestionActivity.EXTRA_REVIEW_LINES;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        TextView resultCurrencyTopValue = null;
        View resultCurrencyIconButton = null;
        MaterialButton resultCurrencyAddButton = null;

        if (getSupportActionBar() != null) {
            View customActionBarView = LayoutInflater.from(this).inflate(R.layout.action_bar_game_mode, null);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setCustomView(customActionBarView, new ActionBar.LayoutParams(
                    ActionBar.LayoutParams.MATCH_PARENT,
                    ActionBar.LayoutParams.MATCH_PARENT
            ));

            TextView toolbarTitle = customActionBarView.findViewById(R.id.gameModeToolbarTitle);
            toolbarTitle.setText(R.string.result_title);
            resultCurrencyTopValue = customActionBarView.findViewById(R.id.currencyTopValue);
            resultCurrencyIconButton = customActionBarView.findViewById(R.id.currencyIconButton);
            resultCurrencyAddButton = customActionBarView.findViewById(R.id.currencyAddButton);
            TextView topUpBalanceView = resultCurrencyTopValue;
            resultCurrencyAddButton.setOnClickListener(v -> CurrencyTopUpDialog.show(this, topUpBalanceView));
        }

        int score = getIntent().getIntExtra(EXTRA_SCORE, 0);
        int total = getIntent().getIntExtra(EXTRA_TOTAL, 0);
        int opponentScore = getIntent().getIntExtra(EXTRA_OPPONENT_SCORE, 0);
        String gameMode = getIntent().getStringExtra(GameModeActivity.EXTRA_GAME_MODE);
        int percent = total > 0 ? Math.round((score * 100f) / total) : 0;
        int selectedCurrency = getIntent().getIntExtra(GameModeActivity.EXTRA_SELECTED_CURRENCY, 0);
        String difficultyMode = getIntent().getStringExtra(GameModeActivity.EXTRA_DIFFICULTY_MODE);
        String sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        ArrayList<String> reviewLines = getIntent().getStringArrayListExtra(EXTRA_REVIEW_LINES);

        CircularProgressIndicator resultProgress = findViewById(R.id.resultProgress);
        TextView resultPercent = findViewById(R.id.resultPercent);
        TextView resultValue = findViewById(R.id.resultValue);
        TextView resultDetails = findViewById(R.id.resultDetails);
        TextView resultRewardTitle = findViewById(R.id.resultRewardTitle);
        TextView rewardSelectedValue = findViewById(R.id.rewardSelectedValue);
        ImageView rewardSelectedCoin = findViewById(R.id.rewardSelectedCoin);
        TextView rewardMultiplierText = findViewById(R.id.rewardMultiplierText);
        TextView rewardPercentText = findViewById(R.id.rewardPercentText);
        TextView rewardEqualsText = findViewById(R.id.rewardEqualsText);
        TextView rewardResultValue = findViewById(R.id.rewardResultValue);
        ImageView rewardResultCoin = findViewById(R.id.rewardResultCoin);
        MaterialButton retryButton = findViewById(R.id.retryQuizButton);
        MaterialButton reviewAnswersButton = findViewById(R.id.reviewAnswersButton);
        MaterialButton finishButton = findViewById(R.id.finishQuizButton);

        updateCurrencyBar(resultCurrencyTopValue);
        if (resultCurrencyIconButton != null) {
            TextView finalResultCurrencyTopValue = resultCurrencyTopValue;
            resultCurrencyIconButton.setOnClickListener(v -> updateCurrencyBar(finalResultCurrencyTopValue));
        }

        resultValue.setText(getString(R.string.result_score_value, score, total));
        resultDetails.setText(getResultMessage(percent));
        resultRewardTitle.setText(R.string.result_reward_title);
        rewardSelectedCoin.setImageResource(R.drawable.coin);
        rewardMultiplierText.setText(getDifficultyMultiplierLabel(difficultyMode));
        rewardPercentText.setText(getString(R.string.result_reward_percent_prefix, percent));
        rewardEqualsText.setText("=");
        rewardResultCoin.setImageResource(R.drawable.coin);

        rewardSelectedValue.setText(String.valueOf(selectedCurrency));
        rewardResultValue.setText("0");
        finishQuizOnServer(sessionId, score, total, rewardResultValue, resultCurrencyTopValue);

        animateResultProgress(resultProgress, resultPercent, percent);
        retryButton.setOnClickListener(v -> {
            Intent retryIntent = new Intent(this, GameModeActivity.class)
                    .putExtra(GameModeActivity.EXTRA_TOPIC_ID, getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_ID))
                    .putExtra(GameModeActivity.EXTRA_TOPIC_NAME, getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_NAME))
                    .putExtra(GameModeActivity.EXTRA_TOPIC_ABBR, getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_ABBR));
            startActivity(retryIntent);
            finish();
        });
        reviewAnswersButton.setOnClickListener(v -> {
            Intent explanationIntent = new Intent(this, ExplanationActivity.class);
            explanationIntent.putStringArrayListExtra(EXTRA_REVIEW_LINES, reviewLines);
            startActivity(explanationIntent);
        });
        finishButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finishAffinity();
        });
    }

    private void finishQuizOnServer(String sessionId, int score, int total, TextView rewardResultValue,
                                    TextView currencyTopValue) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                QuizRepository repository = QuizRepository.create(this);
                QuizApiModels.FinishQuizRequest request = new QuizApiModels.FinishQuizRequest();
                request.correctAnswers = score;
                request.totalQuestions = total;
                QuizApiModels.ActionResponse response = repository.finishQuiz(sessionId, request);
                QuizApplication.setDisplayedCurrencyBalance(this, response.balance);
                runOnUiThread(() -> {
                    rewardResultValue.setText(String.valueOf(response.rewardAmount));
                    updateCurrencyBar(currencyTopValue);
                    if (repository.isOfflineSessionId(sessionId)) {
                        android.widget.Toast.makeText(this, R.string.offline_result_saved, android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception exception) {
                QuizRepository repository = QuizRepository.create(this);
                if (!repository.isOfflineSessionId(sessionId)) {
                    int stake = getIntent().getIntExtra(GameModeActivity.EXTRA_SELECTED_CURRENCY, 0);
                    String topicId = getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_ID);
                    String mode = getIntent().getStringExtra(GameModeActivity.EXTRA_GAME_MODE);
                    String difficulty = getIntent().getStringExtra(GameModeActivity.EXTRA_DIFFICULTY_MODE);
                    repository.queueRemoteFinish(sessionId, topicId, mode, difficulty, stake, total, score);
                    int reward = calculateLocalReward(stake, difficulty, score, total);
                    int balance = QuizApplication.getCurrencyBalance(this) + reward;
                    QuizApplication.setDisplayedCurrencyBalance(this, balance);
                    runOnUiThread(() -> {
                        rewardResultValue.setText(String.valueOf(reward));
                        updateCurrencyBar(currencyTopValue);
                        android.widget.Toast.makeText(this, R.string.offline_result_saved, android.widget.Toast.LENGTH_LONG).show();
                    });
                } else {
                    runOnUiThread(() -> android.widget.Toast.makeText(
                            this, R.string.result_save_failed, android.widget.Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private int calculateLocalReward(int stake, String difficulty, int correct, int total) {
        if (total <= 0) {
            return 0;
        }
        double multiplier = "basic".equalsIgnoreCase(difficulty) ? 1.5d
                : "advanced".equalsIgnoreCase(difficulty) ? 3.0d : 2.0d;
        int percent = Math.round(correct * 100f / total);
        return (int) Math.round(stake * multiplier * percent / 100d);
    }

    private void animateResultProgress(CircularProgressIndicator resultProgress, TextView resultPercent, int targetPercent) {
        resultProgress.setMax(100);
        resultProgress.setProgress(0);
        resultProgress.setIndicatorColor(getAnimatedResultColor(0));
        resultPercent.setText(getString(R.string.result_percent_value, 0));

        ValueAnimator animator = ValueAnimator.ofInt(0, targetPercent);
        animator.setDuration(1200L);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            int animatedPercent = (int) animation.getAnimatedValue();
            resultProgress.setProgress(animatedPercent);
            resultProgress.setIndicatorColor(getAnimatedResultColor(animatedPercent));
            resultPercent.setText(getString(R.string.result_percent_value, animatedPercent));
        });
        animator.start();
    }

    private int getAnimatedResultColor(int percent) {
        int red = ContextCompat.getColor(this, android.R.color.holo_red_light);
        int orange = ContextCompat.getColor(this, android.R.color.holo_orange_light);
        int green = ContextCompat.getColor(this, android.R.color.holo_green_light);
        int blue = ContextCompat.getColor(this, android.R.color.holo_blue_light);

        if (percent <= 30) {
            return (int) new ArgbEvaluator().evaluate(percent / 30f, red, orange);
        }
        if (percent <= 65) {
            return (int) new ArgbEvaluator().evaluate((percent - 30f) / 35f, orange, green);
        }
        return (int) new ArgbEvaluator().evaluate((percent - 65f) / 35f, green, blue);
    }

    private void updateCurrencyBar(TextView currencyTopValue) {
        if (currencyTopValue != null) {
            currencyTopValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this)));
        }
    }

    private double getDifficultyMultiplier(String difficultyMode) {
        if ("basic".equalsIgnoreCase(difficultyMode)) {
            return 1.5d;
        }
        if ("advanced".equalsIgnoreCase(difficultyMode)) {
            return 3.0d;
        }
        return 2.0d;
    }

    private String getDifficultyMultiplierLabel(String difficultyMode) {
        double multiplier = getDifficultyMultiplier(difficultyMode);
        if (multiplier == 1.5d) {
            return "x1,5";
        }
        if (multiplier == 3.0d) {
            return "x3";
        }
        return "x2";
    }

    private String getResultMessage(int percent) {
        if (percent <= 30) {
            return getString(R.string.result_feedback_retry);
        }
        if (percent <= 65) {
            return getString(R.string.result_feedback_not_bad);
        }
        if (percent <= 99) {
            return getString(R.string.result_feedback_good);
        }
        return getString(R.string.result_feedback_perfect);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
