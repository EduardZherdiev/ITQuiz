package com.maxim.itquiz;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.view.animation.LinearInterpolator;

import com.maxim.itquiz.data.local.QuizDatabase;
import com.maxim.itquiz.data.DifficultyLevel;
import com.maxim.itquiz.data.local.entity.OptionEntity;
import com.maxim.itquiz.data.local.entity.OptionTextEntity;
import com.maxim.itquiz.data.local.entity.QuestionTextEntity;
import com.maxim.itquiz.data.QuizRepository;
import com.maxim.itquiz.data.remote.dto.QuizApiModels;

public class QuestionActivity extends AppCompatActivity {

    private static final int ANSWER_DELAY_MS = 700;
    private static final int COMPUTER_MIN_DELAY_MS = 1000;
    private static final int COMPUTER_MAX_DELAY_MS = 10000;
    private static final int COMPUTER_AFTER_USER_DELAY_MS = 1000;
    private static final int QUESTION_TIME_SECONDS = 20;
    private static final int TIMER_PROGRESS_MULTIPLIER = 100;
    private static final long TIMER_ANIMATION_FRAME_MS = 16L;
    private static final String STATE_CURRENT_QUESTION_INDEX = "state_current_question_index";
    private static final String STATE_SCORE = "state_score";
    private static final String STATE_OPPONENT_SCORE = "state_opponent_score";
    private static final String STATE_HUMAN_QUESTION_RESOLVED = "state_human_question_resolved";
    private static final String STATE_COMPUTER_QUESTION_RESOLVED = "state_computer_question_resolved";
    private static final String STATE_NEXT_QUESTION_SCHEDULED = "state_next_question_scheduled";
    private static final String STATE_ANSWER_LOCKED = "state_answer_locked";
    private static final String STATE_SELECTED_ANSWERS = "state_selected_answers";
    private static final String STATE_TIMER_DEADLINE = "state_timer_deadline";
    private static final String STATE_TIMER_QUESTION_INDEX = "state_timer_question_index";
    private static final String STATE_QUESTION_ORDER = "state_question_order";
    public static final String EXTRA_SESSION_ID = "extra_session_id";
    public static final String EXTRA_REVIEW_LINES = "extra_review_lines";
    private static final String EXTRA_SCORE = "extra_score";
    private static final String EXTRA_TOTAL = "extra_total";
    private static final int BASIC_QUESTION_LIMIT = 10;
    private static final int COMMON_QUESTION_LIMIT = 15;
    private static final int ADVANCED_QUESTION_LIMIT = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<QuestionItem> questions = new ArrayList<>();

    private int currentQuestionIndex = 0;
    private int score = 0;
    private int opponentScore = 0;
    private boolean humanQuestionResolved;
    private boolean computerQuestionResolved;
    private boolean nextQuestionScheduled;

    private TextView toolbarTitle;
    private TextView toolbarCurrencyValue;
    private TextView correctAnswersText;
    private TextView correctAnswersOpponentText;
    private TextView soloCorrectAnswersText;
    private ImageView questionPlayerCrown;
    private ImageView questionOpponentCrown;
    private ImageView soloUserCrown;
    private TextView questionCounter;
    private TextView questionText;
    private TextView questionTimerText;
    private TextView questionPlayerNameOverlay;
    private TextView soloUserNameOverlay;
    private CircularProgressIndicator questionTimerProgress;
    private View questionLoadingPanel;
    private ImageView questionPlayerIcon;
    private ImageView questionOpponentIcon;
    private ImageView soloUserIcon;
    private View questionPlayerFrame;
    private View questionOpponentFrame;
    private View soloUserFrame;
    private View questionPlayerScoreBlock;
    private View questionOpponentScoreBlock;
    private MaterialButton[] answerButtons;
    private String topicName;
    private String topicId;
    private String topicAbbr;
    private String difficultyLetter;
    private String difficultyMode;
    private String gameMode;
    private int questionLimit;
    private int selectedCurrency;
    private String sessionId;
    private ValueAnimator questionTimerAnimator;
    private long questionTimerDeadlineElapsedRealtime;
    private int timerQuestionIndex = -1;
    private boolean restoringQuestionState;
    private ArrayList<String> questionOrder = new ArrayList<>();
    private Runnable computerAnswerRunnable;
    private Runnable nextQuestionRunnable;
    private boolean answerLocked;
    private int[] selectedAnswerIndexes;
    private String selectedCrownId;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question);

        topicName = getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_NAME);
        topicId = getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_ID);
        topicAbbr = getIntent().getStringExtra(GameModeActivity.EXTRA_TOPIC_ABBR);
        selectedCurrency = getIntent().getIntExtra(GameModeActivity.EXTRA_SELECTED_CURRENCY, 1000);
        difficultyLetter = getIntent().getStringExtra(GameModeActivity.EXTRA_DIFFICULTY_LETTER);
        difficultyMode = getIntent().getStringExtra(GameModeActivity.EXTRA_DIFFICULTY_MODE);
        gameMode = getIntent().getStringExtra(GameModeActivity.EXTRA_GAME_MODE);
        questionLimit = getIntent().getIntExtra(GameModeActivity.EXTRA_QUESTION_LIMIT, getQuestionLimit(difficultyMode));
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);

        restoreQuestionState(savedInstanceState);

        if (topicAbbr == null || topicAbbr.isEmpty()) {
            topicAbbr = "DB";
        }
        if (difficultyLetter == null || difficultyLetter.isEmpty()) {
            difficultyLetter = "C";
        }
        if (difficultyMode == null || difficultyMode.isEmpty()) {
            difficultyMode = "common";
        }

        if (getSupportActionBar() != null) {
            View customActionBarView = LayoutInflater.from(this).inflate(R.layout.action_bar_question, null);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setCustomView(customActionBarView, new ActionBar.LayoutParams(
                    ActionBar.LayoutParams.MATCH_PARENT,
                    ActionBar.LayoutParams.MATCH_PARENT
            ));

            toolbarTitle = customActionBarView.findViewById(R.id.questionToolbarTitle);
            toolbarTitle.setText(getString(R.string.question_toolbar_title, topicAbbr, selectedCurrency, difficultyMode));
            toolbarCurrencyValue = customActionBarView.findViewById(R.id.currencyTopValue);
            toolbarCurrencyValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this)));
            View questionCurrencyIcon = customActionBarView.findViewById(R.id.currencyIconButton);
            questionCurrencyIcon.setOnClickListener(v -> toolbarCurrencyValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this))));
            View questionCurrencyAdd = customActionBarView.findViewById(R.id.currencyAddButton);
            questionCurrencyAdd.setOnClickListener(v -> CurrencyTopUpDialog.show(this, toolbarCurrencyValue));
        }
        questionCounter = findViewById(R.id.questionCounterText);
        soloCorrectAnswersText = findViewById(R.id.soloCorrectAnswersText);
        questionPlayerCrown = findViewById(R.id.questionPlayerCrown);
        questionOpponentCrown = findViewById(R.id.questionOpponentCrown);
        soloUserCrown = findViewById(R.id.soloUserCrown);
        soloUserIcon = findViewById(R.id.soloUserIcon);
        questionPlayerIcon = findViewById(R.id.questionPlayerIcon);
        questionOpponentIcon = findViewById(R.id.questionOpponentIcon);
        questionPlayerFrame = findViewById(R.id.questionPlayerFrame);
        questionOpponentFrame = findViewById(R.id.questionOpponentFrame);
        soloUserFrame = findViewById(R.id.soloUserFrame);
        questionPlayerNameOverlay = findViewById(R.id.questionPlayerNameOverlay);
        soloUserNameOverlay = findViewById(R.id.soloUserNameOverlay);
        questionPlayerScoreBlock = findViewById(R.id.questionPlayerScoreBlock);
        questionOpponentScoreBlock = findViewById(R.id.questionOpponentScoreBlock);
        correctAnswersText = findViewById(R.id.correctAnswersText);
        correctAnswersOpponentText = findViewById(R.id.correctAnswersOpponentText);
        questionText = findViewById(R.id.questionText);
        questionTimerText = findViewById(R.id.questionTimerText);
        questionTimerProgress = findViewById(R.id.questionTimerProgress);
        questionLoadingPanel = findViewById(R.id.questionLoadingPanel);
        answerButtons = new MaterialButton[]{
                findViewById(R.id.answerButton1),
                findViewById(R.id.answerButton2),
                findViewById(R.id.answerButton3),
                findViewById(R.id.answerButton4)
        };

        questionTimerProgress.setMax(QUESTION_TIME_SECONDS * TIMER_PROGRESS_MULTIPLIER);
        applyUserVisualAssets();
        applyQuestionHeaderMode();
        showQuestionLoading();

        // Load questions asynchronously; UI will be updated when questions are ready.
        setUpQuestions();
    }

    private void applyQuestionHeaderMode() {
        boolean soloMode = isSoloMode();
        if (soloMode) {
            soloCorrectAnswersText.setVisibility(View.VISIBLE);
            soloUserFrame.setVisibility(View.VISIBLE);
            soloUserIcon.setVisibility(View.VISIBLE);
            questionPlayerScoreBlock.setVisibility(View.GONE);
            questionOpponentScoreBlock.setVisibility(View.GONE);
            soloUserCrown.setVisibility("crown_none".equals(selectedCrownId) ? View.GONE : View.VISIBLE);
            questionPlayerCrown.setVisibility(View.GONE);
            if (questionOpponentCrown != null) {
                questionOpponentCrown.setVisibility(View.GONE);
            }
            soloCorrectAnswersText.setText(String.valueOf(score));
            return;
        }

        soloCorrectAnswersText.setVisibility(View.GONE);
        soloUserFrame.setVisibility(View.GONE);
        soloUserIcon.setVisibility(View.GONE);
        soloUserCrown.setVisibility(View.GONE);
        questionPlayerScoreBlock.setVisibility(View.VISIBLE);
        questionOpponentScoreBlock.setVisibility(View.VISIBLE);
        questionPlayerCrown.setVisibility("crown_none".equals(selectedCrownId) ? View.GONE : View.VISIBLE);
        if (questionOpponentCrown != null) {
            questionOpponentCrown.setVisibility(View.VISIBLE);
        }
    }

    private void applyUserVisualAssets() {
        AvatarAssetsHelper.applyUserAvatar(this, questionPlayerFrame, questionPlayerIcon, questionPlayerCrown, R.drawable.user);
        AvatarAssetsHelper.applyUserAvatar(this, soloUserFrame, soloUserIcon, soloUserCrown, R.drawable.user);
        AvatarAssetsHelper.applyUserDisplayName(this, questionPlayerNameOverlay);
        AvatarAssetsHelper.applyUserDisplayName(this, soloUserNameOverlay);
        AvatarAssetsHelper.applyDefaultAvatar(this, questionOpponentFrame, questionOpponentIcon, R.drawable.user);
        if (questionOpponentCrown != null) {
            questionOpponentCrown.setImageResource(R.drawable.silver_crown);
            questionOpponentCrown.bringToFront();
        }

        selectedCrownId = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("pref_assets_selected_CROWN", "crown_none");
    }

    private boolean isSoloMode() {
        return gameMode == null || gameMode.isEmpty() || "solo".equalsIgnoreCase(gameMode);
    }

    private void setUpQuestions() {
        final String requestedTopicId = topicId == null ? "" : topicId.trim();
        final String lang = QuizLanguage.current(this);
        final int difficultyLevel = DifficultyLevel.fromLegacyMode(difficultyMode);

        new Thread(() -> {
            android.util.Log.d("QuestionActivity", "setUpQuestions: loading from DB topicId=" + requestedTopicId + " lang=" + lang);
            try {
                QuizDatabase database = QuizDatabase.getInstance(this);
                List<QuestionTextEntity> qTexts = database.quizDao().getQuestionTextsByTopicAndDifficulty(requestedTopicId, difficultyLevel, lang);
                String questionLanguage = lang;
                if ((qTexts == null || qTexts.isEmpty()) && !"en".equals(lang)) {
                    qTexts = database.quizDao().getQuestionTextsByTopicAndDifficulty(requestedTopicId, difficultyLevel, "en");
                    questionLanguage = "en";
                }
                android.util.Log.d("QuestionActivity", "setUpQuestions: qTexts count=" + (qTexts != null ? qTexts.size() : 0));
                if (qTexts != null && !qTexts.isEmpty()) {
                    if (questionOrder == null || questionOrder.isEmpty()) {
                        Collections.shuffle(qTexts);
                    } else {
                        java.util.Map<String, QuestionTextEntity> byId = new java.util.HashMap<>();
                        for (QuestionTextEntity questionText : qTexts) {
                            byId.put(questionText.questionId, questionText);
                        }
                        List<QuestionTextEntity> restoredOrder = new ArrayList<>();
                        for (String questionId : questionOrder) {
                            QuestionTextEntity questionText = byId.remove(questionId);
                            if (questionText != null) {
                                restoredOrder.add(questionText);
                            }
                        }
                        // Preserve newly downloaded questions if the catalog
                        // changed while this activity was being recreated.
                        restoredOrder.addAll(byId.values());
                        qTexts = restoredOrder;
                    }
                    questions.clear();
                    int limit = Math.min(qTexts.size(), questionLimit > 0 ? questionLimit : qTexts.size());
                    questionOrder.clear();
                    for (int i = 0; i < limit; i++) {
                        QuestionTextEntity qt = qTexts.get(i);
                        questionOrder.add(qt.questionId);
                        List<OptionEntity> options = database.quizDao().getOptionsByQuestionId(qt.questionId);
                        String[] optionTexts = new String[options.size()];
                        int correctIndex = -1;
                        for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                            OptionEntity o = options.get(optionIndex);
                            OptionTextEntity ot = database.quizDao().getOptionText(o.id, questionLanguage);
                            if (ot == null && !"en".equals(questionLanguage)) {
                                ot = database.quizDao().getOptionText(o.id, "en");
                            }
                            optionTexts[optionIndex] = ot != null ? ot.optionText : "";
                            if (o.isCorrect) correctIndex = optionIndex;
                        }
                        questions.add(new QuestionItem(qt.questionText, optionTexts, correctIndex, qt.explanation));
                    }
                    runOnUiThread(() -> {
                        hideQuestionLoading();
                        if (selectedAnswerIndexes == null || selectedAnswerIndexes.length != questions.size()) {
                            selectedAnswerIndexes = new int[questions.size()];
                            Arrays.fill(selectedAnswerIndexes, -1);
                        }
                        showCurrentQuestion();
                    });
                    return;
                }
                if (NetworkState.isAvailable(this)) {
                    QuizRepository.create(this).syncBootstrapAsync(lang, new QuizRepository.SyncCallback() {
                        @Override
                        public void onSuccess() {
                            runOnUiThread(QuestionActivity.this::setUpQuestions);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            runOnUiThread(() -> {
                                hideQuestionLoading();
                                android.widget.Toast.makeText(QuestionActivity.this, R.string.question_load_failed, android.widget.Toast.LENGTH_LONG).show();
                                finish();
                            });
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                android.util.Log.e("QuestionActivity", "setUpQuestions: error loading from DB", e);
            }
            runOnUiThread(() -> {
                hideQuestionLoading();
                android.widget.Toast.makeText(this, R.string.question_load_failed, android.widget.Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    private void showQuestionLoading() {
        if (questionLoadingPanel != null) {
            questionLoadingPanel.setVisibility(View.VISIBLE);
        }
    }

    private void hideQuestionLoading() {
        if (questionLoadingPanel != null) {
            questionLoadingPanel.setVisibility(View.GONE);
        }
    }

    private void showCurrentQuestion() {
        boolean restoring = restoringQuestionState
                && timerQuestionIndex == currentQuestionIndex
                && currentQuestionIndex >= 0
                && currentQuestionIndex < questions.size();
        if (!restoring) {
            answerLocked = false;
            humanQuestionResolved = false;
            computerQuestionResolved = isSoloMode() || !"computer".equalsIgnoreCase(gameMode);
            nextQuestionScheduled = false;
            clearPendingQuestionCallbacks();
        }
        QuestionItem questionItem = questions.get(currentQuestionIndex);
        correctAnswersText.setText(String.valueOf(score));
        if (correctAnswersOpponentText != null) {
            correctAnswersOpponentText.setText(String.valueOf(opponentScore));
        }
        TextView soloCorrectAnswersText = findViewById(R.id.soloCorrectAnswersText);
        if (soloCorrectAnswersText != null) {
            soloCorrectAnswersText.setText(String.valueOf(score));
        }
        questionCounter.setText(getString(R.string.question_counter, currentQuestionIndex + 1, questions.size()));
        questionText.setText(questionItem.question);

        for (int i = 0; i < answerButtons.length; i++) {
            MaterialButton answerButton = answerButtons[i];
            answerButton.setText(questionItem.options[i]);
            answerButton.setEnabled(true);
            answerButton.setBackgroundTintList(null);
            answerButton.setBackgroundResource(R.drawable.bg_answer_default);

            final int selectedIndex = i;
            answerButton.setOnClickListener(v -> handleAnswerClick(selectedIndex));
        }

        if (!answerLocked) {
            startQuestionTimer();
        }
        if (!restoring && !isSoloMode() && "computer".equalsIgnoreCase(gameMode)) {
            long randomDelay = COMPUTER_MIN_DELAY_MS + (long) (Math.random() * (COMPUTER_MAX_DELAY_MS - COMPUTER_MIN_DELAY_MS + 1));
            scheduleComputerAnswer(randomDelay);
        } else if (restoring && nextQuestionScheduled) {
            // Handler callbacks do not survive configuration changes.
            nextQuestionScheduled = false;
            scheduleNextQuestion(ANSWER_DELAY_MS);
        } else if (restoring && !isSoloMode() && "computer".equalsIgnoreCase(gameMode)
                && !computerQuestionResolved) {
            scheduleComputerAnswer(COMPUTER_AFTER_USER_DELAY_MS);
        }
        restoringQuestionState = false;
    }

    private void handleAnswerClick(int selectedIndex) {
        if (answerLocked) {
            return;
        }
        answerLocked = true;
        humanQuestionResolved = true;
        stopQuestionTimer();

        QuestionItem questionItem = questions.get(currentQuestionIndex);
        boolean isCorrect = selectedIndex == questionItem.correctOptionIndex;
        selectedAnswerIndexes[currentQuestionIndex] = selectedIndex;

        for (MaterialButton answerButton : answerButtons) {
            answerButton.setEnabled(false);
        }

        if (isCorrect) {
            score++;
            applyBlinkBackground(answerButtons[selectedIndex],
                    R.drawable.bg_answer_correct_blink,
                    R.drawable.bg_answer_correct_1);
        } else {
            applyBlinkBackground(answerButtons[selectedIndex],
                    R.drawable.bg_answer_wrong_blink,
                    R.drawable.bg_answer_wrong_1);
            applyBlinkBackground(answerButtons[questionItem.correctOptionIndex],
                    R.drawable.bg_answer_correct_blink,
                    R.drawable.bg_answer_correct_1);
        }

        if (isSoloMode() || !"computer".equalsIgnoreCase(gameMode)) {
            scheduleNextQuestion(ANSWER_DELAY_MS);
            return;
        }

        if (computerQuestionResolved) {
            scheduleNextQuestion(ANSWER_DELAY_MS);
            return;
        }

        clearComputerAnswerCallback();
        scheduleComputerAnswer(COMPUTER_AFTER_USER_DELAY_MS);
    }

    private void scheduleComputerAnswer(long delayMs) {
        clearComputerAnswerCallback();
        computerAnswerRunnable = () -> {
            if (isFinishing() || isDestroyed() || computerQuestionResolved) {
                return;
            }

            float correctProbability = getComputerCorrectProbability();
            boolean computerCorrect = Math.random() < correctProbability;
            if (computerCorrect) {
                opponentScore++;
            }

            computerQuestionResolved = true;
            if (correctAnswersOpponentText != null) {
                correctAnswersOpponentText.setText(String.valueOf(opponentScore));
            }

            if (humanQuestionResolved) {
                scheduleNextQuestion(ANSWER_DELAY_MS);
            }
        };
        handler.postDelayed(computerAnswerRunnable, delayMs);
    }

    private float getComputerCorrectProbability() {
        if ("basic".equalsIgnoreCase(difficultyMode)) {
            return 0.5f;
        }
        if ("advanced".equalsIgnoreCase(difficultyMode)) {
            return 0.9f;
        }
        return 0.7f;
    }

    private void scheduleNextQuestion(long delayMs) {
        if (nextQuestionScheduled) {
            return;
        }
        nextQuestionScheduled = true;
        nextQuestionRunnable = this::goToNextQuestion;
        handler.postDelayed(nextQuestionRunnable, delayMs);
    }

    private void clearComputerAnswerCallback() {
        if (computerAnswerRunnable != null) {
            handler.removeCallbacks(computerAnswerRunnable);
            computerAnswerRunnable = null;
        }
    }

    private void clearNextQuestionCallback() {
        if (nextQuestionRunnable != null) {
            handler.removeCallbacks(nextQuestionRunnable);
            nextQuestionRunnable = null;
        }
        nextQuestionScheduled = false;
    }

    private void clearPendingQuestionCallbacks() {
        clearComputerAnswerCallback();
        clearNextQuestionCallback();
    }

    private void goToNextQuestion() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        clearPendingQuestionCallbacks();
        currentQuestionIndex++;
        if (currentQuestionIndex < questions.size()) {
            showCurrentQuestion();
            return;
        }

        startActivity(new Intent(this, ResultActivity.class)
                .putExtra(EXTRA_SCORE, score)
                .putExtra(EXTRA_TOTAL, questions.size())
                .putExtra("opponent_score", opponentScore)
                .putExtra(GameModeActivity.EXTRA_TOPIC_NAME, topicName)
                .putExtra(GameModeActivity.EXTRA_TOPIC_ID, topicId)
                .putExtra(GameModeActivity.EXTRA_TOPIC_ABBR, topicAbbr)
                .putExtra(GameModeActivity.EXTRA_SELECTED_CURRENCY, selectedCurrency)
                .putExtra(GameModeActivity.EXTRA_DIFFICULTY_LETTER, difficultyLetter)
                .putExtra(GameModeActivity.EXTRA_DIFFICULTY_MODE, difficultyMode)
                .putExtra(GameModeActivity.EXTRA_GAME_MODE, gameMode)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putStringArrayListExtra(EXTRA_REVIEW_LINES, buildReviewLines()));
        finish();
    }

    private ArrayList<String> buildReviewLines() {
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionItem item = questions.get(i);
            int selectedIndex = selectedAnswerIndexes != null && i < selectedAnswerIndexes.length ? selectedAnswerIndexes[i] : -1;
            String selectedText = selectedIndex >= 0 && selectedIndex < item.options.length
                    ? item.options[selectedIndex]
                    : getString(R.string.explanation_not_answered);
            String correctText = item.options[item.correctOptionIndex];
            String line = (i + 1) + ". " + item.question + "\n"
                    + getString(R.string.explanation_selected_prefix, selectedText) + "\n"
                    + getString(R.string.explanation_correct_prefix, correctText);
            if (item.explanation != null && !item.explanation.trim().isEmpty()) {
                line = line + "\n\n" + item.explanation;
            }
            lines.add(line);
        }
        return lines;
    }

    private void startQuestionTimer() {
        stopQuestionTimer();
        final int maxProgress = QUESTION_TIME_SECONDS * TIMER_PROGRESS_MULTIPLIER;

        long now = SystemClock.elapsedRealtime();
        if (timerQuestionIndex != currentQuestionIndex) {
            timerQuestionIndex = currentQuestionIndex;
            questionTimerDeadlineElapsedRealtime = now + QUESTION_TIME_SECONDS * 1000L;
        }

        long remainingMs = Math.max(0L, questionTimerDeadlineElapsedRealtime - now);
        int remainingProgress = (int) Math.min(maxProgress,
                Math.ceil(remainingMs / 1000.0 * TIMER_PROGRESS_MULTIPLIER));
        questionTimerText.setText(String.valueOf((int) Math.ceil(remainingMs / 1000.0)));
        questionTimerProgress.setProgress(remainingProgress);

        if (remainingMs <= 0L) {
            handleQuestionTimeout();
            return;
        }

        questionTimerAnimator = ValueAnimator.ofInt(remainingProgress, 0);
        questionTimerAnimator.setDuration(remainingMs);
        questionTimerAnimator.setInterpolator(new LinearInterpolator());
        questionTimerAnimator.setRepeatCount(0);
        questionTimerAnimator.setStartDelay(0L);
        questionTimerAnimator.setCurrentPlayTime(0L);
        questionTimerAnimator.addUpdateListener(animation -> {
            int progress = (int) animation.getAnimatedValue();
            questionTimerProgress.setProgress(progress);

            int secondsLeft = (int) Math.ceil(progress / (float) TIMER_PROGRESS_MULTIPLIER);
            if (secondsLeft < 0) {
                secondsLeft = 0;
            }
            questionTimerText.setText(String.valueOf(secondsLeft));
        });
        questionTimerAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                handleQuestionTimeout();
            }
        });
        questionTimerAnimator.setFrameDelay(TIMER_ANIMATION_FRAME_MS);
        questionTimerAnimator.start();
    }

    private void handleQuestionTimeout() {
        if (answerLocked) {
            return;
        }
        answerLocked = true;
        humanQuestionResolved = true;
        selectedAnswerIndexes[currentQuestionIndex] = -1;
        questionTimerText.setText("0");
        questionTimerProgress.setProgress(0);

        for (MaterialButton answerButton : answerButtons) {
            answerButton.setEnabled(false);
        }

        if (computerQuestionResolved) {
            scheduleNextQuestion(ANSWER_DELAY_MS);
        }
    }

    private void stopQuestionTimer() {
        if (questionTimerAnimator != null) {
            questionTimerAnimator.cancel();
            questionTimerAnimator.removeAllListeners();
            questionTimerAnimator.removeAllUpdateListeners();
            questionTimerAnimator = null;
        }
    }

    private void restoreQuestionState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        currentQuestionIndex = savedInstanceState.getInt(STATE_CURRENT_QUESTION_INDEX, 0);
        score = savedInstanceState.getInt(STATE_SCORE, 0);
        opponentScore = savedInstanceState.getInt(STATE_OPPONENT_SCORE, 0);
        humanQuestionResolved = savedInstanceState.getBoolean(STATE_HUMAN_QUESTION_RESOLVED, false);
        computerQuestionResolved = savedInstanceState.getBoolean(STATE_COMPUTER_QUESTION_RESOLVED, false);
        nextQuestionScheduled = savedInstanceState.getBoolean(STATE_NEXT_QUESTION_SCHEDULED, false);
        answerLocked = savedInstanceState.getBoolean(STATE_ANSWER_LOCKED, false);
        selectedAnswerIndexes = savedInstanceState.getIntArray(STATE_SELECTED_ANSWERS);
        questionOrder = savedInstanceState.getStringArrayList(STATE_QUESTION_ORDER);
        if (questionOrder == null) {
            questionOrder = new ArrayList<>();
        }
        questionTimerDeadlineElapsedRealtime = savedInstanceState.getLong(STATE_TIMER_DEADLINE, 0L);
        timerQuestionIndex = savedInstanceState.getInt(STATE_TIMER_QUESTION_INDEX, -1);
        restoringQuestionState = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_CURRENT_QUESTION_INDEX, currentQuestionIndex);
        outState.putInt(STATE_SCORE, score);
        outState.putInt(STATE_OPPONENT_SCORE, opponentScore);
        outState.putBoolean(STATE_HUMAN_QUESTION_RESOLVED, humanQuestionResolved);
        outState.putBoolean(STATE_COMPUTER_QUESTION_RESOLVED, computerQuestionResolved);
        outState.putBoolean(STATE_NEXT_QUESTION_SCHEDULED, nextQuestionScheduled);
        outState.putBoolean(STATE_ANSWER_LOCKED, answerLocked);
        outState.putIntArray(STATE_SELECTED_ANSWERS, selectedAnswerIndexes);
        outState.putStringArrayList(STATE_QUESTION_ORDER, questionOrder);
        outState.putLong(STATE_TIMER_DEADLINE, questionTimerDeadlineElapsedRealtime);
        outState.putInt(STATE_TIMER_QUESTION_INDEX, timerQuestionIndex);
        super.onSaveInstanceState(outState);
    }

    private void applyBlinkBackground(MaterialButton button, int backgroundRes, int finalBackgroundRes) {
        button.setBackgroundTintList(null);
        button.setBackgroundResource(backgroundRes);
        if (button.getBackground() instanceof AnimationDrawable) {
            ((AnimationDrawable) button.getBackground()).start();
        }
        // The second animation frame is intentionally pale. Replace it with a
        // stable saturated result so the answer never remains visually faded.
        handler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                button.setBackgroundResource(finalBackgroundRes);
            }
        }, 520L);
    }

    @Override
    public boolean onSupportNavigateUp() {
        exitQuestionScreen();
        return true;
    }

    @Override
    public void onBackPressed() {
        exitQuestionScreen();
        super.onBackPressed();
    }

    private void exitQuestionScreen() {
        String sessionToCancel = sessionId;
        String topicToCancel = topicId;
        String modeToCancel = gameMode;
        String difficultyToCancel = difficultyMode;
        int stakeToCancel = selectedCurrency;
        int totalToCancel = questionLimit;
        sessionId = null;
        stopQuestionTimer();
        clearPendingQuestionCallbacks();
        handler.removeCallbacksAndMessages(null);
        if (sessionToCancel != null && !sessionToCancel.trim().isEmpty()) {
            new Thread(() -> {
                try {
                    QuizApiModels.ActionResponse response = QuizRepository.create(this).cancelQuiz(sessionToCancel);
                    QuizApplication.setDisplayedCurrencyBalance(this, response.balance);
                    QuizRepository.create(this).applyServerBalanceLocally(response.balance);
                } catch (Exception exception) {
                    if (!QuizRepository.create(this).isOfflineSessionId(sessionToCancel)) {
                        QuizRepository.create(this).queueRemoteCancel(
                                sessionToCancel, topicToCancel, modeToCancel, difficultyToCancel, stakeToCancel, totalToCancel);
                    }
                    android.util.Log.e("QuestionActivity", "Could not cancel quiz session", exception);
                }
            }).start();
        }
        finish();
    }

    private int getQuestionLimit(String difficultyMode) {
        if ("basic".equalsIgnoreCase(difficultyMode)) {
            return BASIC_QUESTION_LIMIT;
        }
        if ("advanced".equalsIgnoreCase(difficultyMode)) {
            return ADVANCED_QUESTION_LIMIT;
        }
        return COMMON_QUESTION_LIMIT;
    }

    @Override
    protected void onDestroy() {
        stopQuestionTimer();
        clearPendingQuestionCallbacks();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Back is handled by exitQuestionScreen(). This covers Home, task
        // switching, and other ways of leaving an unfinished offline game.
        if (!isFinishing() && !isChangingConfigurations()
                && sessionId != null && sessionId.startsWith("offline_")) {
            QuizRepository.create(getApplicationContext()).markOfflineSessionAbandonedAsync(sessionId);
        }
    }

        private static class QuestionItem {
        final String question;
        final String[] options;
        final int correctOptionIndex;
        final String explanation;

        QuestionItem(String question, String[] options, int correctOptionIndex, String explanation) {
            this.question = question;
            this.options = options;
            this.correctOptionIndex = correctOptionIndex;
            this.explanation = explanation == null ? "" : explanation;
        }
    }
}
