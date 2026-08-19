package com.maxim.itquiz;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("MainActivity", "onCreate: networkAvailable="
                + NetworkState.isAvailable() + ", displayedBalance="
                + QuizApplication.getCurrencyBalance(this));
        setContentView(R.layout.activity_main);
        PlayGamesAccountManager.attemptAutomaticLink(this);
        HeroImageLoader.load(findViewById(R.id.mainHeroImage));
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        Button playB = findViewById(R.id.buttonPlay);
        Button assetsB = findViewById(R.id.buttonAssets);
        Button aboutB = findViewById(R.id.buttonAbout);
        Button settingsB = findViewById(R.id.buttonSettins);
        Button shareB = findViewById(R.id.buttonShare);
        Button createQuizB = findViewById(R.id.buttonCreateQuiz);
        createQuizB.setVisibility(View.GONE);
        TextView mainCurrencyTopValue = findViewById(R.id.currencyTopValue);
        View mainCurrencyIconButton = findViewById(R.id.currencyIconButton);
        Button mainCurrencyAddButton = findViewById(R.id.currencyAddButton);
        mainCurrencyAddButton.setOnClickListener(v -> CurrencyTopUpDialog.show(this, mainCurrencyTopValue));
        ImageView mainTopLeftIcon = findViewById(R.id.mainTopLeftIcon);
        View mainTopLeftProfile = findViewById(R.id.mainTopLeftProfile);
        ImageView mainTopLeftCrown = findViewById(R.id.mainTopLeftCrown);
        TextView mainTopLeftNameOverlay = findViewById(R.id.mainTopLeftNameOverlay);

        updateMainCurrency(mainCurrencyTopValue);
        mainCurrencyIconButton.setOnClickListener(v -> updateMainCurrency(mainCurrencyTopValue));
        updateMainProfileIcon(mainTopLeftProfile, mainTopLeftIcon, mainTopLeftCrown);
        AvatarAssetsHelper.applyUserDisplayName(this, mainTopLeftNameOverlay);

        playB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, TopicsActivity.class);
                startActivity(intent);
            }
        });

        assetsB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, AssetsActivity.class);
                startActivity(intent);
            }
        });

        aboutB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
            }
        });

        shareB.setOnClickListener(v -> shareApp());

        settingsB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayGamesAccountManager.attemptAutomaticLink(this);
        TextView mainCurrencyTopValue = findViewById(R.id.currencyTopValue);
        updateMainCurrency(mainCurrencyTopValue);
        updateMainProfileIcon(findViewById(R.id.mainTopLeftProfile), findViewById(R.id.mainTopLeftIcon), findViewById(R.id.mainTopLeftCrown));
        AvatarAssetsHelper.applyUserDisplayName(this, findViewById(R.id.mainTopLeftNameOverlay));
    }

    private void updateMainProfileIcon(View mainTopLeftProfile, ImageView mainTopLeftIcon, ImageView mainTopLeftCrown) {
        if (mainTopLeftIcon == null || mainTopLeftProfile == null || mainTopLeftCrown == null) {
            return;
        }
        AvatarAssetsHelper.applyUserAvatar(this, mainTopLeftProfile, mainTopLeftIcon, mainTopLeftCrown, R.drawable.user);
    }

    private void updateMainCurrency(TextView mainCurrencyTopValue) {
        if (mainCurrencyTopValue != null) {
            mainCurrencyTopValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this)));
        }
    }

    private void shareApp() {
        String shareText = getString(
                R.string.share_app_message,
                getString(R.string.app_name),
                getString(R.string.share_app_link)
        );
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_chooser_title)));
    }
}
