package com.maxim.quiz;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions;
import com.maxim.quiz.data.QuizRepository;
import com.maxim.quiz.data.remote.dto.QuizApiModels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CurrencyTopUpActivity extends AppCompatActivity implements PurchasesUpdatedListener {

    private TextView balanceView;
    private MaterialButton adButton;
    private MaterialButton testButton;
    private View loadingOverlay;
    private RewardedAd rewardedAd;
    private boolean rewardedAdLoading;
    private boolean rewardedAdEarned;
    private BillingClient billingClient;
    private boolean billingReady;
    private final Map<String, View> offerRows = new HashMap<>();
    private final Map<String, ProductDetails> productDetails = new HashMap<>();

    private static final PackageOffer[] OFFERS = {
            new PackageOffer("coins_55", "55"),
            new PackageOffer("coins_165", "165"),
            new PackageOffer("coins_560", "560"),
            new PackageOffer("coins_1900", "1 900"),
            new PackageOffer("coins_6800", "6 800")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_top_up);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.currency_top_up_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        balanceView = findViewById(R.id.currencyTopUpBalance);
        adButton = findViewById(R.id.currencyTopUpAdButton);
        testButton = findViewById(R.id.currencyTopUpTestButton);
        loadingOverlay = findViewById(R.id.currencyTopUpLoadingOverlay);
        updateBalance();

        adButton.setOnClickListener(v -> showRewardedAd());
        testButton.setOnClickListener(v -> topUp(false));
        findViewById(R.id.currencyTopUpTestCard).setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        LinearLayout packageList = findViewById(R.id.currencyPackageList);
        for (PackageOffer offer : OFFERS) {
            addOffer(packageList, offer);
        }
        initializeRewardedAds();
        initializeBilling();
    }

    private void addOffer(LinearLayout container, PackageOffer offer) {
        View row = LayoutInflater.from(this).inflate(R.layout.currency_top_up_row, container, false);
        ((TextView) row.findViewById(R.id.currencyPackageAmount)).setText(offer.amount);
        ((TextView) row.findViewById(R.id.currencyPackagePrice)).setText(
                R.string.currency_top_up_price_unavailable
        );
        MaterialButton buyButton = row.findViewById(R.id.currencyPackageBuyButton);
        buyButton.setEnabled(false);
        buyButton.setOnClickListener(v -> launchPurchase(offer.productId));
        offerRows.put(offer.productId, row);
        container.addView(row);
    }

    private void initializeRewardedAds() {
        adButton.setEnabled(false);
        MobileAds.initialize(this, status -> loadRewardedAd());
    }

    private void loadRewardedAd() {
        if (rewardedAdLoading || rewardedAd != null || isFinishing()) {
            return;
        }
        rewardedAdLoading = true;
        RewardedAd.load(
                this,
                BuildConfig.REWARDED_AD_UNIT_ID,
                new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAdLoading = false;
                        rewardedAd = ad;
                        String userId = PreferenceManager.getDefaultSharedPreferences(CurrencyTopUpActivity.this)
                                .getString("pref_user_id", "unknown");
                        ad.setServerSideVerificationOptions(
                                new ServerSideVerificationOptions.Builder()
                                        .setCustomData(userId)
                                        .build()
                        );
                        adButton.setEnabled(true);
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        rewardedAdLoading = false;
                        rewardedAd = null;
                        adButton.setEnabled(false);
                    }
                }
        );
    }

    private void showRewardedAd() {
        if (!NetworkState.isAvailable(this)) {
            Toast.makeText(this, R.string.network_action_requires_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        if (rewardedAd == null) {
            Toast.makeText(this, R.string.currency_top_up_ad_loading, Toast.LENGTH_SHORT).show();
            loadRewardedAd();
            return;
        }

        RewardedAd ad = rewardedAd;
        rewardedAd = null;
        rewardedAdEarned = false;
        setTopUpButtonsEnabled(false);
        setLoading(true);
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                setLoading(false);
                setTopUpButtonsEnabled(true);
                Toast.makeText(CurrencyTopUpActivity.this, R.string.currency_top_up_ad_failed, Toast.LENGTH_SHORT).show();
                loadRewardedAd();
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                loadRewardedAd();
                if (!rewardedAdEarned) {
                    setLoading(false);
                    setTopUpButtonsEnabled(true);
                }
            }
        });
        ad.show(this, rewardItem -> {
            rewardedAdEarned = true;
            grantRewardedCoins();
        });
    }

    private void grantRewardedCoins() {
        new Thread(() -> {
            try {
                QuizRepository repository = QuizRepository.create(this);
                if ("ad_test".equals(BuildConfig.REWARDED_SERVER_SOURCE)) {
                    QuizApiModels.ActionResponse response = repository.topUpAdCurrency();
                    runOnUiThread(() -> finishRewardedGrant(response.balance, R.string.currency_top_up_ad_success));
                } else {
                    // Production uses the verified AdMob SSV callback as the
                    // authority. The client only reconciles the balance.
                    Thread.sleep(1200L);
                    int balance = repository.reconcileServerBalanceBlocking();
                    runOnUiThread(() -> finishRewardedGrant(balance, R.string.currency_top_up_ad_pending));
                }
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    setTopUpButtonsEnabled(true);
                    Toast.makeText(this, R.string.currency_top_up_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }, "rewarded-coin-grant").start();
    }

    private void finishRewardedGrant(int balance, int messageRes) {
        QuizApplication.setDisplayedCurrencyBalance(this, balance);
        updateBalance(balance);
        setLoading(false);
        setTopUpButtonsEnabled(true);
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }

    private void initializeBilling() {
        billingClient = BillingClient.newBuilder(this)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .enableAutoServiceReconnection()
                .build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                billingReady = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (billingReady) {
                    queryProducts();
                    queryUnfinishedPurchases();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                billingReady = false;
            }
        });
    }

    private void queryProducts() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (PackageOffer offer : OFFERS) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(offer.productId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();
        billingClient.queryProductDetailsAsync(params, (result, queryResult) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || queryResult == null) {
                return;
            }
            productDetails.clear();
            for (ProductDetails details : queryResult.getProductDetailsList()) {
                productDetails.put(details.getProductId(), details);
                updateOfferRow(details);
            }
        });
    }

    private void updateOfferRow(ProductDetails details) {
        View row = offerRows.get(details.getProductId());
        if (row == null) {
            return;
        }
        List<ProductDetails.OneTimePurchaseOfferDetails> prices = details.getOneTimePurchaseOfferDetailsList();
        if (prices == null || prices.isEmpty()) {
            return;
        }
        TextView priceView = row.findViewById(R.id.currencyPackagePrice);
        MaterialButton buyButton = row.findViewById(R.id.currencyPackageBuyButton);
        priceView.setText(getString(R.string.currency_top_up_price, prices.get(0).getFormattedPrice()));
        buyButton.setEnabled(true);
    }

    private void launchPurchase(String productId) {
        ProductDetails details = productDetails.get(productId);
        if (!billingReady || details == null) {
            Toast.makeText(this, R.string.currency_top_up_billing_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        BillingFlowParams.ProductDetailsParams detailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build();
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(detailsParams))
                .setObfuscatedAccountId(hashUserId())
                .build();
        setLoading(true);
        BillingResult result = billingClient.launchBillingFlow(this, flowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            setLoading(false);
            Toast.makeText(this, R.string.currency_top_up_billing_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void queryUnfinishedPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (result, purchases) -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        for (Purchase purchase : purchases) {
                            processPurchase(purchase);
                        }
                    }
                }
        );
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                processPurchase(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            setLoading(false);
        } else {
            setLoading(false);
            Toast.makeText(this, R.string.currency_top_up_billing_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void processPurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED
                || purchase.getProducts() == null || purchase.getProducts().isEmpty()) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, R.string.currency_top_up_purchase_pending, Toast.LENGTH_SHORT).show();
                });
            }
            return;
        }
        String productId = purchase.getProducts().get(0);
        new Thread(() -> {
            try {
                QuizApiModels.ActionResponse response = QuizRepository.create(this)
                        .verifyGooglePlayPurchase(productId, purchase.getPurchaseToken());
                runOnUiThread(() -> finishRewardedGrant(response.balance, R.string.currency_top_up_purchase_success));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, R.string.currency_top_up_purchase_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }, "google-play-purchase").start();
    }

    private String hashUserId() {
        String userId = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("pref_user_id", "unknown");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "quiz_user";
        }
    }

    private void topUp(boolean simulatedAd) {
        if (simulatedAd && !NetworkState.isAvailable(this)) {
            Toast.makeText(this, R.string.network_action_requires_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        adButton.setEnabled(false);
        testButton.setEnabled(false);
        setLoading(true);
        new Thread(() -> {
            try {
                QuizRepository repository = QuizRepository.create(this);
                QuizApiModels.ActionResponse response = simulatedAd
                        ? repository.topUpAdCurrency()
                        : repository.topUpTestCurrency();
                QuizApplication.setDisplayedCurrencyBalance(this, response.balance);
                runOnUiThread(() -> {
                    updateBalance(response.balance);
                    Toast.makeText(
                            this,
                            simulatedAd
                                    ? R.string.currency_top_up_ad_success
                                    : R.string.currency_top_up_test_success,
                            Toast.LENGTH_SHORT
                    ).show();
                    setTopUpButtonsEnabled(true);
                    setLoading(false);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    int message = !NetworkState.isAvailable(this)
                            ? R.string.network_action_requires_connection
                            : R.string.currency_top_up_failed;
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    setTopUpButtonsEnabled(true);
                    setLoading(false);
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private void updateBalance() {
        updateBalance(QuizApplication.getCurrencyBalance(this));
    }

    private void updateBalance(int balance) {
        if (balanceView != null) {
            balanceView.setText(String.valueOf(balance));
        }
    }

    private void setTopUpButtonsEnabled(boolean enabled) {
        adButton.setEnabled(enabled);
        testButton.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        if (billingClient != null && billingClient.isReady()) {
            billingClient.endConnection();
        }
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private static final class PackageOffer {
        final String amount;
        final String productId;

        PackageOffer(String productId, String amount) {
            this.productId = productId;
            this.amount = amount;
        }
    }
}
