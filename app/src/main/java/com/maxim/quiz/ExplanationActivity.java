package com.maxim.quiz;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;

import java.util.ArrayList;

public class ExplanationActivity extends AppCompatActivity {

    private static final String EXTRA_REVIEW_LINES = QuestionActivity.EXTRA_REVIEW_LINES;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explanation);
        setTitle(R.string.explanation_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout explanationContent = findViewById(R.id.explanationContent);
        ArrayList<String> reviewLines = getIntent().getStringArrayListExtra(EXTRA_REVIEW_LINES);
        if (reviewLines == null || reviewLines.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.explanation_empty);
            emptyView.setTextColor(ContextCompat.getColor(this, R.color.black));
            explanationContent.addView(emptyView);
            return;
        }

        boolean nightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int textColor = nightMode ? ContextCompat.getColor(this, R.color.white) : ContextCompat.getColor(this, R.color.black);
        int correctBackground = ContextCompat.getColor(this, nightMode ? R.color.review_correct_background_night : R.color.review_correct_background_day);
        int incorrectBackground = ContextCompat.getColor(this, nightMode ? R.color.review_incorrect_background_night : R.color.review_incorrect_background_day);

        for (String reviewLine : reviewLines) {
            if (reviewLine == null || reviewLine.trim().isEmpty()) {
                continue;
            }
            ReviewBlock block = parseReviewBlock(reviewLine);
            TextView itemView = new TextView(this);
            itemView.setText(block.text);
            itemView.setTextColor(textColor);
            itemView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            itemView.setLineSpacing(0f, 1.15f);
            int horizontal = dp(14);
            int vertical = dp(12);
            itemView.setPadding(horizontal, vertical, horizontal, vertical);
            itemView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) itemView.getLayoutParams();
            params.bottomMargin = dp(12);
            itemView.setLayoutParams(params);

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            background.setColor(block.correct ? correctBackground : incorrectBackground);
            itemView.setBackground(background);

            explanationContent.addView(itemView);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private ReviewBlock parseReviewBlock(String reviewLine) {
        String selectedPrefix = getString(R.string.explanation_selected_prefix, "");
        String correctPrefix = getString(R.string.explanation_correct_prefix, "");

        String[] parts = reviewLine.split("\\n\\n", 2);
        String body = parts[0];
        String explanation = parts.length > 1 ? parts[1].trim() : "";

        String[] bodyLines = body.split("\\n");
        String question = bodyLines.length > 0 ? bodyLines[0].trim() : "";
        String selected = "";
        String correct = "";
        if (bodyLines.length > 1 && bodyLines[1].startsWith(selectedPrefix)) {
            selected = bodyLines[1].substring(selectedPrefix.length()).trim();
        }
        if (bodyLines.length > 2 && bodyLines[2].startsWith(correctPrefix)) {
            correct = bodyLines[2].substring(correctPrefix.length()).trim();
        }

        boolean correctAnswer = !selected.isEmpty() && selected.equals(correct);
        StringBuilder builder = new StringBuilder();
        builder.append(question);
        if (!selected.isEmpty()) {
            builder.append("\n").append(getString(R.string.explanation_selected_prefix, selected));
        }
        if (!correct.isEmpty()) {
            builder.append("\n").append(getString(R.string.explanation_correct_prefix, correct));
        }
        if (!explanation.isEmpty()) {
            builder.append("\n\n").append(explanation);
        }

        ReviewBlock block = new ReviewBlock();
        block.text = builder.toString();
        block.correct = correctAnswer;
        return block;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static class ReviewBlock {
        String text;
        boolean correct;
    }
}