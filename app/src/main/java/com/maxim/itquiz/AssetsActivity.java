package com.maxim.itquiz;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.maxim.itquiz.data.local.QuizDatabase;
import com.maxim.itquiz.data.local.dao.QuizDao;
import com.maxim.itquiz.data.local.entity.AssetEntity;
import com.maxim.itquiz.data.local.entity.UserAssetEntity;
import com.maxim.itquiz.data.local.entity.UserEntity;
import com.maxim.itquiz.data.local.entity.CurrencyTransactionEntity;
import android.util.Log;
import com.maxim.itquiz.data.QuizRepository;

public class AssetsActivity extends AppCompatActivity {

    private static final String PREF_OWNED_PREFIX = "pref_assets_owned_";
    private static final String PREF_SELECTED_PREFIX = "pref_assets_selected_";

    private TextView toolbarCurrencyValue;
    private LinearLayout assetsListContainer;
    private SharedPreferences preferences;
    private final java.util.Map<SlotType, String> currentSelectedBySlot = new java.util.concurrent.ConcurrentHashMap<>();

    private enum SlotType {
        FRAME,
        CROWN
    }

    private enum UnlockType {
        FREE,
        BUY,
        ACHIEVEMENT
    }

    private static class AssetItem {
        final String id;
        final String code;
        final SlotType slot;
        final UnlockType unlockType;
        final int titleRes;
        final int descriptionRes;
        final int price;
        final int requirementRes;
        final int colorRes;

        AssetItem(String id, String code, SlotType slot, UnlockType unlockType, int titleRes, int descriptionRes,
                  int price, int requirementRes, int colorRes) {
            this.id = id;
            this.code = code;
            this.slot = slot;
            this.unlockType = unlockType;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.price = price;
            this.requirementRes = requirementRes;
            this.colorRes = colorRes;
        }
    }

    private static final String TAG = "AssetsActivity";
    private volatile boolean renderingInProgress = false;
    private View actionLoadingOverlay;
    private final AtomicBoolean assetActionInProgress = new AtomicBoolean(false);
    private final ExecutorService assetSyncExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assets);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        assetsListContainer = findViewById(R.id.assetsListContainer);
        actionLoadingOverlay = findViewById(R.id.assetsActionLoadingOverlay);

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
            toolbarTitle.setText(getString(R.string.title_assets));

            toolbarCurrencyValue = customActionBarView.findViewById(R.id.currencyTopValue);
            View assetsCurrencyIconButton = customActionBarView.findViewById(R.id.currencyIconButton);
            MaterialButton assetsCurrencyAddButton = customActionBarView.findViewById(R.id.currencyAddButton);

            updateCurrencyBar(toolbarCurrencyValue);
            assetsCurrencyIconButton.setOnClickListener(v -> updateCurrencyBar(toolbarCurrencyValue));
            assetsCurrencyAddButton.setOnClickListener(v -> CurrencyTopUpDialog.show(this, toolbarCurrencyValue));
        }

        prepareAssetsScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCurrencyBar(toolbarCurrencyValue);
        QuizRepository repository = QuizRepository.create(this);
        repository.syncPendingAssetOperationsAsync(new QuizRepository.SyncCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    updateCurrencyBar(toolbarCurrencyValue);
                    renderAssetsShopFromDb();
                });
            }

            @Override
            public void onError(Throwable throwable) {
                Log.d(TAG, "No asset outbox sync on resume", throwable);
            }
        });
        if (!repository.isBootstrapSyncInProgress(QuizLanguage.current(this))) {
            renderAssetsShopFromDb();
        }
    }

    private void prepareAssetsScreen() {
        renderFallbackAssetsImmediately();
        syncAssetsOnlyWhenCacheIsEmpty();
    }

    private void renderAssetsShopFromDb() {
        final long startTimeMs = System.currentTimeMillis();
        Log.d(TAG, "renderAssetsShopFromDb: start");
        LayoutInflater inflater = LayoutInflater.from(this);

        if (renderingInProgress) {
            Log.d(TAG, "renderAssetsShopFromDb: already rendering - skip");
            return;
        }
        renderingInProgress = true;

        // Load assets and user assets from DB
        new Thread(() -> {
            try {
                long dbStartMs = System.currentTimeMillis();
                List<AssetEntity> assets = QuizDatabase.getInstance(this).quizDao().getAllAssets();
                String localUserId = preferences.getString("pref_user_id", "user_test");
                List<UserAssetEntity> userAssets = QuizDatabase.getInstance(this).quizDao().getUserAssets(localUserId);
                if (userAssets == null) {
                    userAssets = new ArrayList<>();
                }
                if (assets == null || assets.isEmpty()) {
                    renderingInProgress = false;
                    runOnUiThread(AssetsActivity.this::renderFallbackAssetsImmediately);
                    return;
                }
                long dbEndMs = System.currentTimeMillis();
                Log.d(TAG, "renderAssetsShopFromDb: DB read took " + (dbEndMs - dbStartMs) + "ms; assets=" + (assets != null ? assets.size() : 0) + " userAssets=" + (userAssets != null ? userAssets.size() : 0));
                // map user assets to quick lookup
                final java.util.Set<String> owned = new java.util.HashSet<>();
                final java.util.Map<String, Boolean> selectedMap = new java.util.HashMap<>();
                for (UserAssetEntity ua : userAssets) {
                    owned.add(ua.assetId);
                    selectedMap.put(ua.assetId, ua.selected);
                }

                currentSelectedBySlot.clear();
                for (AssetEntity asset : assets) {
                    String selectedId = selectedMap.get(asset.id) != null && selectedMap.get(asset.id)
                            ? asset.id : null;
                    if (selectedId == null) continue;
                    if ("FRAME".equalsIgnoreCase(asset.assetType)) {
                        currentSelectedBySlot.put(SlotType.FRAME, selectedId);
                        preferences.edit().putString(PREF_SELECTED_PREFIX + SlotType.FRAME.name(), asset.assetCode).apply();
                    }
                    if ("CROWN".equalsIgnoreCase(asset.assetType)) {
                        currentSelectedBySlot.put(SlotType.CROWN, selectedId);
                        preferences.edit().putString(PREF_SELECTED_PREFIX + SlotType.CROWN.name(), asset.assetCode).apply();
                    }
                }

                // build AssetItem list based on DB
                final java.util.List<AssetItem> frameItems = new java.util.ArrayList<>();
                final java.util.List<AssetItem> crownItems = new java.util.ArrayList<>();
                for (AssetEntity a : assets) {
                    SlotType slot = SlotType.FRAME;
                    if ("CROWN".equalsIgnoreCase(a.assetType)) slot = SlotType.CROWN;
                    int price = a.price;
                    UnlockType unlockType = price > 0 ? UnlockType.BUY : UnlockType.FREE;
                    int titleRes = R.string.assets_item_frame_classic; // fallback
                    int descRes = R.string.assets_item_frame_classic_desc;
                    String code = a.assetCode == null ? a.id : a.assetCode;
                    // reuse existing resources by server asset code
                    switch (code) {
                        case "frame_classic": titleRes = R.string.assets_item_frame_classic; descRes = R.string.assets_item_frame_classic_desc; break;
                        case "frame_neon": titleRes = R.string.assets_item_frame_neon; descRes = R.string.assets_item_frame_neon_desc; break;
                        case "frame_royal": titleRes = R.string.assets_item_frame_royal; descRes = R.string.assets_item_frame_royal_desc; break;
                        case "crown_none": titleRes = R.string.assets_item_crown_none; descRes = R.string.assets_item_crown_none_desc; break;
                        case "crown_bronze": titleRes = R.string.assets_item_crown_bronze; descRes = R.string.assets_item_crown_bronze_desc; break;
                        case "crown_silver": titleRes = R.string.assets_item_crown_silver; descRes = R.string.assets_item_crown_silver_desc; break;
                        case "crown_gold": titleRes = R.string.assets_item_crown_gold; descRes = R.string.assets_item_crown_gold_desc; break;
                        case "crown_brilliant": titleRes = R.string.assets_item_crown_brilliant; descRes = R.string.assets_item_crown_brilliant_desc; break;
                    }
                    AssetItem item = new AssetItem(a.id, code, slot, unlockType, titleRes, descRes, price, 0, android.R.color.holo_purple);
                    if (slot == SlotType.FRAME) frameItems.add(item); else crownItems.add(item);
                }

                runOnUiThread(() -> {
                    long totalMs = System.currentTimeMillis() - startTimeMs;
                    assetsListContainer.removeAllViews();
                    addSection(inflater, R.string.assets_section_frames, frameItems, owned, selectedMap);
                    addSection(inflater, R.string.assets_section_crowns, crownItems, owned, selectedMap);
                    Log.d(TAG, "renderAssetsShopFromDb: UI updated with frameItems=" + frameItems.size() + " crownItems=" + crownItems.size() + "; total time=" + totalMs + "ms");
                    renderingInProgress = false;
                });
            } catch (Exception e) {
                renderingInProgress = false;
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (!NetworkState.isAvailable(AssetsActivity.this)) {
                        renderFallbackAssetsImmediately();
                    }
                });
            }
        }).start();
    }



    private void addSection(LayoutInflater inflater, int titleRes, List<AssetItem> items, java.util.Set<String> owned, java.util.Map<String, Boolean> selectedMap) {
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText(titleRes);
        sectionTitle.setTextSize(18f);
        sectionTitle.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface));
        sectionTitle.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 12;
        sectionTitle.setLayoutParams(params);
        assetsListContainer.addView(sectionTitle);

        for (AssetItem item : items) {
            View row = inflater.inflate(R.layout.asset_shop_item, assetsListContainer, false);
            row.setTag(item);
            bindAssetRow(row, item, owned, selectedMap);
            assetsListContainer.addView(row);
        }
    }

    private void bindAssetRow(View row, AssetItem item, java.util.Set<String> owned, java.util.Map<String, Boolean> selectedMap) {
        ImageView previewCrown = row.findViewById(R.id.itemPreviewCrown);
        ImageView previewIcon = row.findViewById(R.id.itemPreviewIcon);
        TextView title = row.findViewById(R.id.itemTitle);
        TextView description = row.findViewById(R.id.itemDescription);
        TextView state = row.findViewById(R.id.itemStateText);
        TextView priceText = row.findViewById(R.id.itemPriceText);
        LinearLayout priceContainer = row.findViewById(R.id.itemPriceContainer);
        MaterialButton buyButton = row.findViewById(R.id.itemBuyButton);
        RadioButton radio = row.findViewById(R.id.itemRadio);

        title.setText(item.titleRes);
        description.setText(item.descriptionRes);
        bindPreviewIcon(previewIcon, previewCrown, item);

        boolean isOwned = owned.contains(item.id);
        boolean isSelected = item.id.equals(currentSelectedBySlot.get(item.slot));

        if (isOwned) {
            priceContainer.setVisibility(View.GONE);
            buyButton.setVisibility(View.GONE);
            radio.setVisibility(View.VISIBLE);
            radio.setChecked(isSelected);
            state.setVisibility(View.GONE);

            row.setOnClickListener(v -> {
                updateSelectedAssetInDb(item.id, item.code, item.slot, row);
            });
            return;
        }

        radio.setVisibility(View.GONE);
        if (item.unlockType == UnlockType.BUY) {
            priceContainer.setVisibility(View.VISIBLE);
            buyButton.setVisibility(View.VISIBLE);
            state.setVisibility(View.GONE);
            priceText.setText(String.valueOf(item.price));
            buyButton.setOnClickListener(v -> buyItem(item));
        } else {
            priceContainer.setVisibility(View.GONE);
            buyButton.setVisibility(View.GONE);
            state.setVisibility(View.VISIBLE);
            state.setText(item.requirementRes != 0 ? item.requirementRes : R.string.assets_locked_achievement);
        }
    }

    private void buyItem(AssetItem item) {
        Log.d(TAG, "buyItem: attempting purchase item=" + item.id + " price=" + item.price);
        if (!assetActionInProgress.compareAndSet(false, true)) {
            return;
        }
        setActionLoading(true);
        new Thread(() -> {
            try {
                QuizRepository repository = QuizRepository.create(this);
                QuizRepository.AssetPurchaseResult result = repository.purchaseAssetWithOfflineSupport(
                        Integer.parseInt(item.id)
                );
                QuizApplication.setDisplayedCurrencyBalance(this, result.balance);
                if (!result.queuedForSync) {
                    repository.applyAssetPurchaseLocally(Integer.parseInt(item.id), result.balance);
                }
                runOnUiThread(() -> {
                    updateCurrencyBar(toolbarCurrencyValue);
                    renderAssetsShopFromDb();
                    Toast.makeText(AssetsActivity.this,
                            result.queuedForSync
                                    ? R.string.assets_purchase_pending
                                    : R.string.assets_purchase_success,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "buyItem: error", e);
                runOnUiThread(() -> Toast.makeText(this,
                        e.getMessage() != null && (e.getMessage().contains("409")
                                || e.getMessage().contains("Not enough coins"))
                                ? R.string.assets_not_enough_coins : R.string.assets_purchase_failed,
                        Toast.LENGTH_SHORT).show());
            } finally {
                assetActionInProgress.set(false);
                runOnUiThread(() -> setActionLoading(false));
            }
        }).start();
    }

    private boolean isOwned(String itemId) {
        return preferences.getBoolean(PREF_OWNED_PREFIX + itemId, false);
    }

    private void updateSelectedAssetInDb(String assetId, String assetCode, SlotType slot, View selectedRow) {
        Log.d(TAG, "updateSelectedAssetInDb: assetId=" + assetId + " slot=" + slot);
        String previousAssetId = currentSelectedBySlot.get(slot);
        String previousAssetCode = preferences.getString(PREF_SELECTED_PREFIX + slot.name(), "");

        // Update the visible state first. The network call is only persistence and
        // must not make the user wait for a radio button change.
        currentSelectedBySlot.put(slot, assetId);
        preferences.edit().putString(PREF_SELECTED_PREFIX + slot.name(), assetCode).apply();
        updateSelectionUi(slot, assetId);

        assetSyncExecutor.execute(() -> {
            try {
                QuizDao dao = QuizDatabase.getInstance(this).quizDao();
                String userId = preferences.getString("pref_user_id", "user_test");
                dao.clearSelectedForUserAndType(userId, slot.name());
                dao.selectAsset(userId, assetId);

                boolean queued = QuizRepository.create(this).selectAssetWithOfflineSupport(
                        Integer.parseInt(assetId),
                        slot.name(),
                        previousAssetId
                );
                Log.d(TAG, "updateSelectedAssetInDb: selected " + assetId + " on server");
            } catch (Exception e) {
                Log.e(TAG, "updateSelectedAssetInDb: failed", e);
                try {
                    QuizDao dao = QuizDatabase.getInstance(this).quizDao();
                    String userId = preferences.getString("pref_user_id", "user_test");
                    dao.clearSelectedForUserAndType(userId, slot.name());
                    if (previousAssetId != null && !previousAssetId.isEmpty()) {
                        dao.selectAsset(userId, previousAssetId);
                    }
                } catch (Exception rollbackError) {
                    Log.e(TAG, "updateSelectedAssetInDb: local rollback failed", rollbackError);
                }
                runOnUiThread(() -> {
                    if (previousAssetId == null || previousAssetId.isEmpty()) {
                        currentSelectedBySlot.remove(slot);
                        preferences.edit().remove(PREF_SELECTED_PREFIX + slot.name()).apply();
                    } else {
                        currentSelectedBySlot.put(slot, previousAssetId);
                        preferences.edit().putString(PREF_SELECTED_PREFIX + slot.name(), previousAssetCode).apply();
                    }
                    updateSelectionUi(slot, previousAssetId);
                    Toast.makeText(this, R.string.assets_selection_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void syncAssetsOnlyWhenCacheIsEmpty() {
        assetSyncExecutor.execute(() -> {
            try {
                if (QuizDatabase.getInstance(this).quizDao().countAssets() > 0) {
                    return;
                }
                QuizRepository.create(this).syncBootstrapAsync(QuizLanguage.current(this),
                        new QuizRepository.SyncCallback() {
                            @Override
                            public void onSuccess() {
                                runOnUiThread(AssetsActivity.this::renderAssetsShopFromDb);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Log.w(TAG, "Assets background sync failed", throwable);
                                runOnUiThread(() -> {
                                    renderFallbackAssetsImmediately();
                                    hideAssetsLoading();
                                });
                            }
                        });
            } catch (Exception exception) {
                Log.w(TAG, "Could not inspect local asset cache", exception);
                runOnUiThread(() -> {
                    renderFallbackAssetsImmediately();
                    hideAssetsLoading();
                });
            }
        });
    }

    private void hideAssetsLoading() {
    }

    private void setActionLoading(boolean loading) {
        if (actionLoadingOverlay != null) {
            actionLoadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private void renderFallbackAssetsImmediately() {
        if (assetsListContainer == null || assetsListContainer.getChildCount() > 0) {
            return;
        }
        List<AssetEntity> assets = buildFallbackAssets();
        java.util.Set<String> owned = new java.util.HashSet<>();
        owned.add("1");
        owned.add("4");
        java.util.Map<String, Boolean> selectedMap = new java.util.HashMap<>();
        selectedMap.put("1", true);
        selectedMap.put("4", true);
        currentSelectedBySlot.put(SlotType.FRAME, "1");
        currentSelectedBySlot.put(SlotType.CROWN, "4");

        List<AssetItem> frameItems = new ArrayList<>();
        List<AssetItem> crownItems = new ArrayList<>();
        for (AssetEntity asset : assets) {
            AssetItem item = createAssetItem(asset);
            if (item.slot == SlotType.FRAME) {
                frameItems.add(item);
            } else {
                crownItems.add(item);
            }
        }
        assetsListContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        addSection(inflater, R.string.assets_section_frames, frameItems, owned, selectedMap);
        addSection(inflater, R.string.assets_section_crowns, crownItems, owned, selectedMap);
    }

    private AssetItem createAssetItem(AssetEntity a) {
        SlotType slot = "CROWN".equalsIgnoreCase(a.assetType) ? SlotType.CROWN : SlotType.FRAME;
        int titleRes = R.string.assets_item_frame_classic;
        int descRes = R.string.assets_item_frame_classic_desc;
        String code = a.assetCode == null ? a.id : a.assetCode;
        switch (code) {
            case "frame_classic": titleRes = R.string.assets_item_frame_classic; descRes = R.string.assets_item_frame_classic_desc; break;
            case "frame_neon": titleRes = R.string.assets_item_frame_neon; descRes = R.string.assets_item_frame_neon_desc; break;
            case "frame_royal": titleRes = R.string.assets_item_frame_royal; descRes = R.string.assets_item_frame_royal_desc; break;
            case "crown_none": titleRes = R.string.assets_item_crown_none; descRes = R.string.assets_item_crown_none_desc; break;
            case "crown_bronze": titleRes = R.string.assets_item_crown_bronze; descRes = R.string.assets_item_crown_bronze_desc; break;
            case "crown_silver": titleRes = R.string.assets_item_crown_silver; descRes = R.string.assets_item_crown_silver_desc; break;
            case "crown_gold": titleRes = R.string.assets_item_crown_gold; descRes = R.string.assets_item_crown_gold_desc; break;
            case "crown_brilliant": titleRes = R.string.assets_item_crown_brilliant; descRes = R.string.assets_item_crown_brilliant_desc; break;
        }
        return new AssetItem(a.id, code, slot, a.price > 0 ? UnlockType.BUY : UnlockType.FREE,
                titleRes, descRes, a.price, 0, android.R.color.holo_purple);
    }

    private List<AssetEntity> buildFallbackAssets() {
        List<AssetEntity> result = new ArrayList<>();
        result.add(fallbackAsset("1", "FRAME", "frame_classic", 0));
        result.add(fallbackAsset("2", "FRAME", "frame_neon", 1800));
        result.add(fallbackAsset("3", "FRAME", "frame_royal", 4200));
        result.add(fallbackAsset("4", "CROWN", "crown_none", 0));
        result.add(fallbackAsset("5", "CROWN", "crown_bronze", 1800));
        result.add(fallbackAsset("6", "CROWN", "crown_silver", 2500));
        result.add(fallbackAsset("7", "CROWN", "crown_gold", 4200));
        result.add(fallbackAsset("8", "CROWN", "crown_brilliant", 6500));
        return result;
    }

    private AssetEntity fallbackAsset(String id, String type, String code, int price) {
        AssetEntity asset = new AssetEntity();
        asset.id = id;
        asset.assetType = type;
        asset.assetCode = code;
        asset.price = price;
        asset.isActive = true;
        return asset;
    }

    private void updateSelectionUi(SlotType slot, String selectedAssetId) {
        int childCount = assetsListContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = assetsListContainer.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof AssetItem)) {
                continue;
            }
            AssetItem item = (AssetItem) tag;
            if (item.slot != slot) {
                continue;
            }
            RadioButton radio = child.findViewById(R.id.itemRadio);
            if (radio != null) {
                radio.setChecked(item.id.equals(selectedAssetId));
            }
        }
    }



    private void updateCurrencyBar(TextView currencyTopValue) {
        if (currencyTopValue != null) {
            currencyTopValue.setText(String.valueOf(QuizApplication.getCurrencyBalance(this)));
        }
    }

    private void bindPreviewIcon(ImageView previewIcon, ImageView previewCrown, AssetItem item) {
        AvatarAssetsHelper.applyUserPhoto(this, previewIcon, R.drawable.user);

        String frameIdForPreview = item.slot == SlotType.FRAME
                ? item.code
                : preferences.getString(PREF_SELECTED_PREFIX + SlotType.FRAME.name(), "frame_classic");
        previewIcon.setBackgroundResource(getFrameBackgroundRes(frameIdForPreview));
        previewIcon.setPadding(8, 8, 8, 8);

        if (item.slot == SlotType.CROWN) {
            previewCrown.setVisibility(View.VISIBLE);
            previewCrown.setImageResource(getCrownDrawableRes(item.code));
            return;
        }

        previewCrown.setVisibility(View.GONE);
    }

    private int getFrameBackgroundRes(String frameId) {
        if ("frame_neon".equals(frameId)) {
            return R.drawable.bg_question_avatar_slot_neon;
        }
        if ("frame_royal".equals(frameId)) {
            return R.drawable.bg_question_avatar_slot_royal;
        }
        return R.drawable.bg_question_avatar_slot;
    }

    private int getCrownDrawableRes(String crownId) {
        if ("crown_none".equals(crownId)) {
            return 0;
        }
        if ("crown_white".equals(crownId)) {
            return R.drawable.silver_crown;
        }
        if ("crown_bronze".equals(crownId)) {
            return R.drawable.bronze_crown;
        }
        if ("crown_silver".equals(crownId)) {
            return R.drawable.silver_crown;
        }
        if ("crown_gold".equals(crownId)) {
            return R.drawable.gold_crown;
        }
        if ("crown_brilliant".equals(crownId)) {
            return R.drawable.brilliant_crown;
        }
        if ("crown_black".equals(crownId)) {
            return R.drawable.bronze_crown;
        }
        return 0;
    }

    private int resolveThemeColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        assetSyncExecutor.shutdownNow();
        super.onDestroy();
    }
}
