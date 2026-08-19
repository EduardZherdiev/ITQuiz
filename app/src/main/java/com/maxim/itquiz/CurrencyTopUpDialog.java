package com.maxim.itquiz;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Opens the full-screen currency shop. */
public final class CurrencyTopUpDialog {

    private CurrencyTopUpDialog() {
    }

    public static void show(@NonNull Activity activity, android.widget.TextView ignoredBalanceView) {
        activity.startActivity(new Intent(activity, CurrencyTopUpActivity.class));
    }
}
