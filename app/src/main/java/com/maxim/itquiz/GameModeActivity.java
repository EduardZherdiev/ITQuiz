package com.maxim.itquiz;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import com.maxim.itquiz.data.QuizRepository;
import com.maxim.itquiz.data.remote.dto.QuizApiModels;

import java.util.UUID;

public class GameModeActivity extends AppCompatActivity {

    public static final String EXTRA_TOPIC_NAME = "extra_topic_name";
    public static final String EXTRA_TOPIC_ID = "extra_topic_id";
    public static final String EXTRA_TOPIC_ABBR = "extra_topic_abbr";
    public static final String EXTRA_TOPIC_DESCRIPTION = "extra_topic_description";
    public static final String EXTRA_SELECTED_CURRENCY = "extra_selected_currency";
    public static final String EXTRA_DIFFICULTY_LETTER = "extra_difficulty_letter";
    public static final String EXTRA_DIFFICULTY_MODE = "extra_difficulty_mode";
    public static final String EXTRA_GAME_MODE = "extra_game_mode";
    public static final String EXTRA_QUESTION_LIMIT = "extra_question_limit";
    private static final int[] CURRENCY_VALUES = {100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000};
    private static final int BASIC_QUESTION_LIMIT = 10;
    private static final int COMMON_QUESTION_LIMIT = 15;
    private static final int ADVANCED_QUESTION_LIMIT = 20;
    private static final int SOLO_BASIC_CURRENCY_CAP = 1000;
    private static final int SOLO_COMMON_CURRENCY_CAP = 5000;
    private static final int SOLO_ADVANCED_CURRENCY_CAP = 50000;
    private static final int COMPUTER_CURRENCY_CAP = 1000;

    private String selectedDifficulty;
    private String selectedDifficultyMode;
    private String selectedGameMode;
    private int selectedCurrencyIndex;
    private int selectedCurrencyValue;
    private TextView toolbarCurrencyValue;
    private TextView limitsSoloTextView;
    private TextView limitsPlayerTextView;
    private TextView limitsComputerTextView;
    private Slider currencySlider;
    private View currencyLabels;
    private MaterialButton playButton;
    private View loadingPanel;
    private String topicName;
    private String topicAbbreviation;
    private String topicId;
    private String topicDescription;
    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_mode);

        topicName = getIntent().getStringExtra(EXTRA_TOPIC_NAME);
        topicId = getIntent().getStringExtra(EXTRA_TOPIC_ID);
        topicDescription = getIntent().getStringExtra(EXTRA_TOPIC_DESCRIPTION);
        if (topicName == null || topicName.isEmpty()) {
            topicName = getString(R.string.title_game_mode);
        }
        topicAbbreviation = getIntent().getStringExtra(EXTRA_TOPIC_ABBR);
        if (topicAbbreviation == null || topicAbbreviation.isEmpty()) {
            topicAbbreviation = topicName;
        }
        if (topicDescription == null || topicDescription.trim().isEmpty()) {
            topicDescription = getString(R.string.game_mode_subtitle);
        }
        sessionId = null;
        selectedGameMode = "solo";

        selectedCurrencyIndex = 3;
        selectedCurrencyValue = CURRENCY_VALUES[selectedCurrencyIndex];

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
            toolbarTitle.setText(topicAbbreviation);

            toolbarCurrencyValue = customActionBarView.findViewById(R.id.currencyTopValue);
            View currencyIconButton = customActionBarView.findViewById(R.id.currencyIconButton);
            MaterialButton currencyAddButton = customActionBarView.findViewById(R.id.currencyAddButton);
            currencyIconButton.setOnClickListener(v -> updateToolbarCurrencyText());
            currencyAddButton.setOnClickListener(v -> CurrencyTopUpDialog.show(this, toolbarCurrencyValue));
            updateToolbarCurrencyText();

            currencyAddButton.setContentDescription(getString(R.string.game_mode_add_currency));
        }

        selectedDifficulty = getString(R.string.difficulty_common);
        selectedDifficultyMode = "common";
        TextView topicNameTitle = findViewById(R.id.gameModeTopicName);
        topicNameTitle.setText(topicName);
        TextView topicDescriptionView = findViewById(R.id.gameModeTopicDescription);
        topicDescriptionView.setText(topicDescription);
        MaterialButtonToggleGroup difficultyGroup = findViewById(R.id.difficultyGroup);
        difficultyGroup.check(R.id.difficultyCommon);
        difficultyGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.difficultyBasic) {
                selectedDifficulty = getString(R.string.difficulty_basic);
                selectedDifficultyMode = "basic";
            } else if (checkedId == R.id.difficultyCommon) {
                selectedDifficulty = getString(R.string.difficulty_common);
                selectedDifficultyMode = "common";
            } else if (checkedId == R.id.difficultyAdvanced) {
                selectedDifficulty = getString(R.string.difficulty_advanced);
                selectedDifficultyMode = "advanced";
            }
            applyCurrencyConstraintsToSlider();
            updateLimitsText();
            if (playButton != null && currencySlider != null) {
                refreshBalanceUi(currencySlider, playButton);
            }
        });

        currencySlider = findViewById(R.id.currencySlider);
        currencyLabels = findViewById(R.id.currencyLabels);
        limitsSoloTextView = findViewById(R.id.gameModeLimitsSolo);
        limitsPlayerTextView = findViewById(R.id.gameModeLimitsPlayer);
        limitsComputerTextView = findViewById(R.id.gameModeLimitsComputer);
        playButton = findViewById(R.id.buttonPlay);
        loadingPanel = findViewById(R.id.gameModeLoadingPanel);
        RadioButton soloModeButton = findViewById(R.id.radioModeSolo);
        RadioButton playerModeButton = findViewById(R.id.radioModePlayer);
        RadioButton computerModeButton = findViewById(R.id.radioModeComputer);
        findViewById(R.id.gameModePlayerRow).setVisibility(View.GONE);
        soloModeButton.setChecked(true);

        View.OnClickListener modeClickListener = view -> {
            if (view.getId() == R.id.radioModeComputer) {
                selectedGameMode = "computer";
            } else if (view.getId() == R.id.radioModePlayer) {
                selectedGameMode = "player";
            } else {
                selectedGameMode = "solo";
            }
            soloModeButton.setChecked(selectedGameMode.equals("solo"));
            playerModeButton.setChecked(selectedGameMode.equals("player"));
            computerModeButton.setChecked(selectedGameMode.equals("computer"));
            applyCurrencyConstraintsToSlider();
            updateLimitsText();
            refreshBalanceUi(currencySlider, playButton);
        };
        soloModeButton.setOnClickListener(modeClickListener);
        playerModeButton.setOnClickListener(modeClickListener);
        computerModeButton.setOnClickListener(modeClickListener);

        alignCurrencyLabelsWithSlider();
        currencySlider.setLabelFormatter(value -> formatCurrencyCompact(CURRENCY_VALUES[Math.round(value)]));
        currencySlider.addOnChangeListener((slider, value, fromUser) -> {
            int requestedIndex = Math.round(value);
            int maxAffordableIndex = getMaxAffordableCurrencyIndex(QuizApplication.getCurrencyBalance(this));
            int maxAllowedIndex = getMaxAllowedCurrencyIndex(selectedGameMode, selectedDifficultyMode);
            selectedCurrencyIndex = Math.min(requestedIndex, Math.min(maxAffordableIndex, maxAllowedIndex));
            if (requestedIndex != selectedCurrencyIndex) {
                slider.setValue(selectedCurrencyIndex);
            }
            selectedCurrencyValue = CURRENCY_VALUES[selectedCurrencyIndex];
            updateToolbarCurrencyText();
            slider.setContentDescription(getString(R.string.game_mode_currency_value, CURRENCY_VALUES[selectedCurrencyIndex]));
            updateLimitsText();
        });

        applyCurrencyConstraintsToSlider();
        updateLimitsText();
        refreshBalanceUi(currencySlider, playButton);

        playButton.setOnClickListener(v -> startQuestionMode(selectedGameMode, selectedCurrencyValue));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currencySlider != null && playButton != null) {
            applyCurrencyConstraintsToSlider();
            updateLimitsText();
            refreshBalanceUi(currencySlider, playButton);
        }
    }

    private void startQuestionMode(String mode, int chosenCurrency) {
        int maxAllowedCurrency = getMaxAllowedCurrency(mode, selectedDifficultyMode);
        int adjustedCurrency = Math.min(chosenCurrency, maxAllowedCurrency);
        if (adjustedCurrency != chosenCurrency) {
            selectedCurrencyValue = adjustedCurrency;
            selectedCurrencyIndex = getCurrencyIndexForValue(adjustedCurrency);
            if (currencySlider != null) {
                currencySlider.setValue(selectedCurrencyIndex);
            }
            Toast.makeText(this, getString(R.string.game_mode_currency_cap_reached, adjustedCurrency), Toast.LENGTH_SHORT).show();
        }

        if (topicId == null || topicId.trim().isEmpty()) {
            Toast.makeText(this, R.string.game_mode_start_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        int currentBalance = QuizApplication.getCurrencyBalance(this);
        if (adjustedCurrency > currentBalance) {
            Toast.makeText(this, getString(R.string.game_mode_not_enough_currency), Toast.LENGTH_SHORT).show();
            refreshBalanceUi(currencySlider, playButton);
            return;
        }

        final int stake = adjustedCurrency;

        playButton.setEnabled(false);
        setLoading(true);
        new Thread(() -> {
            QuizRepository repository = QuizRepository.create(this);
            QuizApiModels.StartQuizRequest request = new QuizApiModels.StartQuizRequest();
            request.topicId = Integer.parseInt(topicId);
            request.difficulty = selectedDifficultyMode;
            request.stake = stake;
            request.totalQuestions = getQuestionLimit(selectedDifficultyMode);
            request.clientSessionId = "offline_" + UUID.randomUUID();
            boolean offlineMode = "solo".equalsIgnoreCase(mode) || "computer".equalsIgnoreCase(mode);
            request.mode = offlineMode ? ("computer".equalsIgnoreCase(mode) ? "computer" : "solo") : "player";
            if (offlineMode) {
                try {
                    if (!repository.hasLocalQuestions(topicId,
                            com.maxim.itquiz.data.DifficultyLevel.fromLegacyMode(selectedDifficultyMode),
                            QuizLanguage.current(this))) {
                        throw new IllegalStateException("No cached questions for offline game");
                    }
                    String localSessionId = repository.startOfflineQuiz(request);
                    sessionId = localSessionId;
                    runOnUiThread(() -> openQuestionScreen(localSessionId, request.mode, stake, true));
                } catch (Exception localError) {
                    android.util.Log.e("GameModeActivity", "Could not start local quiz", localError);
                    runOnUiThread(() -> {
                        setLoading(false);
                        playButton.setEnabled(true);
                        Toast.makeText(this, R.string.game_mode_start_failed, Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }
            try {
                if (!NetworkState.isAvailable(this)) {
                    throw new IllegalStateException("Network is unavailable");
                }
                runOnUiThread(() -> setLoading(true));
                QuizApiModels.ActionResponse response = repository.startQuiz(request);
                sessionId = response.sessionId == null ? null : String.valueOf(response.sessionId);
                QuizApplication.setDisplayedCurrencyBalance(this, response.balance);
                String remoteSessionId = sessionId;
                runOnUiThread(() -> openQuestionScreen(remoteSessionId, request.mode, stake, false));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    playButton.setEnabled(true);
                    Toast.makeText(this, R.string.game_mode_start_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void openQuestionScreen(String quizSessionId, String mode, int stake, boolean offline) {
        setLoading(false);
        Intent intent = new Intent(this, QuestionActivity.class);
        intent.putExtra(EXTRA_TOPIC_ID, topicId);
        intent.putExtra(EXTRA_TOPIC_NAME, topicName);
        intent.putExtra(EXTRA_TOPIC_ABBR, topicAbbreviation);
        intent.putExtra(EXTRA_SELECTED_CURRENCY, stake);
        intent.putExtra(EXTRA_DIFFICULTY_LETTER, getDifficultyLetter(selectedDifficulty));
        intent.putExtra(EXTRA_DIFFICULTY_MODE, selectedDifficultyMode);
        intent.putExtra(EXTRA_GAME_MODE, mode);
        intent.putExtra(EXTRA_QUESTION_LIMIT, getQuestionLimit(selectedDifficultyMode));
        intent.putExtra(QuestionActivity.EXTRA_SESSION_ID, quizSessionId);
        startActivity(intent);
    }

    private boolean isNetworkFailure(Throwable error) {
        if (!NetworkState.isAvailable(this)) {
            return true;
        }
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return !(message.contains("http 400")
                || message.contains("http 401")
                || message.contains("http 403")
                || message.contains("http 404")
                || message.contains("http 409"));
    }

    private void setLoading(boolean loading) {
        if (loadingPanel != null) {
            loadingPanel.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (loading && playButton != null) {
            playButton.setEnabled(false);
        }
    }

    private void updateToolbarCurrencyText() {
        if (toolbarCurrencyValue != null) {
            toolbarCurrencyValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this)));
        }
    }

    private void updateToolbarCurrencyText(int balance) {
        if (toolbarCurrencyValue != null) {
            toolbarCurrencyValue.setText(String.valueOf(balance));
        }
    }

    private void refreshBalanceUi(Slider currencySlider, MaterialButton playButton) {
        int balance = QuizApplication.getCurrencyBalance(this);
        updateToolbarCurrencyText(balance);

        int maxAffordableIndex = getMaxAffordableCurrencyIndex(balance);
        int maxAllowedIndex = getMaxAllowedCurrencyIndex(selectedGameMode, selectedDifficultyMode);
        int maxPlayableIndex = Math.min(maxAffordableIndex, maxAllowedIndex);
        if (selectedCurrencyIndex > maxPlayableIndex) {
            selectedCurrencyIndex = maxPlayableIndex;
            selectedCurrencyValue = CURRENCY_VALUES[selectedCurrencyIndex];
            currencySlider.setValue(selectedCurrencyIndex);
        }

        currencySlider.setContentDescription(getString(R.string.game_mode_currency_value, CURRENCY_VALUES[selectedCurrencyIndex]));
        selectedCurrencyValue = CURRENCY_VALUES[selectedCurrencyIndex];
        boolean canPlay = balance >= CURRENCY_VALUES[0];
        playButton.setEnabled(canPlay);
        updateLimitsText();
    }

    private int getMaxAffordableCurrencyIndex(int balance) {
        for (int index = CURRENCY_VALUES.length - 1; index >= 0; index--) {
            if (CURRENCY_VALUES[index] <= balance) {
                return index;
            }
        }
        return 0;
    }

    private int getCurrencyIndexForValue(int currencyValue) {
        for (int index = 0; index < CURRENCY_VALUES.length; index++) {
            if (CURRENCY_VALUES[index] == currencyValue) {
                return index;
            }
        }
        return 0;
    }

    private int getMaxAllowedCurrency(String mode, String difficultyMode) {
        if (mode != null && "computer".equalsIgnoreCase(mode)) {
            return COMPUTER_CURRENCY_CAP;
        }

        if (mode != null && "player".equalsIgnoreCase(mode)) {
            return CURRENCY_VALUES[CURRENCY_VALUES.length - 1];
        }

        if ("basic".equalsIgnoreCase(difficultyMode)) {
            return SOLO_BASIC_CURRENCY_CAP;
        }
        if ("advanced".equalsIgnoreCase(difficultyMode)) {
            return SOLO_ADVANCED_CURRENCY_CAP;
        }
        return SOLO_COMMON_CURRENCY_CAP;
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

    private int getMaxAllowedCurrencyIndex(String mode, String difficultyMode) {
        int maxCurrency = getMaxAllowedCurrency(mode, difficultyMode);
        int resultIndex = 0;
        for (int index = 0; index < CURRENCY_VALUES.length; index++) {
            if (CURRENCY_VALUES[index] <= maxCurrency) {
                resultIndex = index;
            }
        }
        return resultIndex;
    }

    private void applyCurrencyConstraintsToSlider() {
        if (currencySlider == null) {
            return;
        }
        int maxAffordableIndex = getMaxAffordableCurrencyIndex(QuizApplication.getCurrencyBalance(this));
        int maxAllowedIndex = getMaxAllowedCurrencyIndex(selectedGameMode, selectedDifficultyMode);
        int maxPlayableIndex = Math.min(maxAffordableIndex, maxAllowedIndex);
        if (selectedCurrencyIndex > maxPlayableIndex) {
            selectedCurrencyIndex = maxPlayableIndex;
            selectedCurrencyValue = CURRENCY_VALUES[selectedCurrencyIndex];
            currencySlider.setValue(selectedCurrencyIndex);
        }
    }

    private void updateLimitsText() {
        if (limitsSoloTextView == null || limitsPlayerTextView == null || limitsComputerTextView == null) {
            return;
        }
        int questionLimit = getQuestionLimit(selectedDifficultyMode);
        int soloStake = getMaxAllowedCurrency("solo", selectedDifficultyMode);
        int playerStake = getMaxAllowedCurrency("player", selectedDifficultyMode);
        int computerStake = getMaxAllowedCurrency("computer", selectedDifficultyMode);

        String multiplier = getDifficultyMultiplierLabel(selectedDifficultyMode);
        limitsSoloTextView.setText(getString(R.string.game_mode_limits_summary, questionLimit, multiplier, formatCurrencyCompact(soloStake)));
        limitsPlayerTextView.setText(getString(R.string.game_mode_limits_summary, questionLimit, multiplier, formatCurrencyCompact(playerStake)));
        limitsComputerTextView.setText(getString(R.string.game_mode_limits_summary, questionLimit, multiplier, formatCurrencyCompact(computerStake)));

        limitsSoloTextView.setVisibility(View.VISIBLE);
        limitsPlayerTextView.setVisibility(View.VISIBLE);
        limitsComputerTextView.setVisibility(View.VISIBLE);
    }

    private void alignCurrencyLabelsWithSlider() {
        if (currencySlider == null || currencyLabels == null) {
            return;
        }

        currencySlider.post(() -> {
            int sidePadding = resolveSliderSidePadding(currencySlider);
            int safeLeftPadding = Math.max(0, sidePadding - 10);
            int safeRightPadding = Math.max(0, sidePadding);
            currencyLabels.setVisibility(View.VISIBLE);
            currencyLabels.setPaddingRelative(
                    safeLeftPadding,
                    currencyLabels.getPaddingTop(),
                    safeRightPadding,
                    currencyLabels.getPaddingBottom()
            );
        });
    }

    private int resolveSliderSidePadding(Slider slider) {
        try {
            Method method = Slider.class.getMethod("getTrackSidePadding");
            Object value = method.invoke(slider);
            if (value instanceof Integer) {
                return ((int) value)-20;
            }
        } catch (Exception ignored) {
            // Fallback value for older Material versions.
        }
        return Math.round(getResources().getDisplayMetrics().density * 16f);
    }

    private String formatCurrencyCompact(int value) {
        if (value >= 1000000) {
            return "1M";
        }
        if (value >= 1000 && value % 1000 == 0) {
            return (value / 1000) + "K";
        }
        if (value == 2500) {
            return "2.5K";
        }
        return String.valueOf(value);
    }

    private String getDifficultyMultiplierLabel(String difficultyMode) {
        if ("basic".equalsIgnoreCase(difficultyMode)) {
            return "x1.5";
        }
        if ("advanced".equalsIgnoreCase(difficultyMode)) {
            return "x3";
        }
        return "x2";
    }

    private String getDifficultyLetter(String difficulty) {
        if (difficulty == null || difficulty.isEmpty()) {
            return "C";
        }
        return String.valueOf(Character.toUpperCase(difficulty.charAt(0)));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
