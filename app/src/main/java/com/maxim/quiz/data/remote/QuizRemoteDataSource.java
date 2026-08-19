package com.maxim.quiz.data.remote;

import com.maxim.quiz.data.remote.dto.BootstrapDto;
import com.maxim.quiz.data.remote.dto.QuizApiModels;

import java.io.IOException;

import retrofit2.Response;
import retrofit2.Call;

public class QuizRemoteDataSource {

    private final QuizApiService apiService;
    private final QuizApiService bootstrapApiService;

    public QuizRemoteDataSource(QuizApiService apiService) {
        this(apiService, apiService);
    }

    public QuizRemoteDataSource(QuizApiService apiService, QuizApiService bootstrapApiService) {
        this.apiService = apiService;
        this.bootstrapApiService = bootstrapApiService;
    }

    public static QuizRemoteDataSource create() {
        return new QuizRemoteDataSource(
                QuizApiClient.service(),
                QuizApiClient.bootstrapService()
        );
    }

    public BootstrapDto fetchBootstrap() throws IOException {
        return fetchBootstrap(null, "en");
    }

    public QuizApiModels.AuthResponse authenticate(String deviceId) throws IOException {
        Response<QuizApiModels.AuthResponse> response = bootstrapApiService
                .authenticate(new QuizApiModels.AuthRequest(deviceId)).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("Anonymous auth failed: HTTP " + response.code());
        }
        return response.body();
    }

    public QuizApiModels.AuthResponse linkPlayGamesAccount(
            String accessToken,
            String serverAuthCode
    ) throws IOException {
        Response<QuizApiModels.AuthResponse> response = bootstrapApiService
                .linkPlayGamesAccount(
                        bearer(accessToken),
                        new QuizApiModels.PlayGamesLinkRequest(serverAuthCode)
                ).execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("Play Games account link failed: HTTP " + response.code());
        }
        return response.body();
    }

    public BootstrapDto fetchBootstrap(String accessToken) throws IOException {
        return fetchBootstrap(accessToken, "en");
    }

    public BootstrapDto fetchBootstrap(String accessToken, String language) throws IOException {
        Response<BootstrapDto> response = bootstrapApiService.getBootstrap(bearer(accessToken), language).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Bootstrap request failed: HTTP " + response.code());
        }
        BootstrapDto body = response.body();
        if (body == null) {
            throw new IOException("Bootstrap request returned an empty body");
        }
        return body;
    }

    public QuizApiModels.ActionResponse topUpTestCurrency(String accessToken, String operationId) throws IOException {
        return topUpCurrency(accessToken, "test", operationId);
    }

    public QuizApiModels.ActionResponse topUpAdCurrency(String accessToken, String operationId) throws IOException {
        return topUpCurrency(accessToken, "ad_test", operationId);
    }

    public QuizApiModels.ActionResponse fetchBalance(String accessToken) throws IOException {
        return executeAction(apiService.getBalance(bearer(accessToken)), "Balance request failed");
    }

    public QuizApiModels.ActionResponse resetGameData(String accessToken) throws IOException {
        return executeAction(apiService.resetGameData(bearer(accessToken)), "Reset game data failed");
    }

    public QuizApiModels.ActionResponse verifyGooglePlayPurchase(
            String accessToken,
            String productId,
            String purchaseToken
    ) throws IOException {
        return executeAction(
                apiService.verifyGooglePlayPurchase(
                        bearer(accessToken),
                        new QuizApiModels.GooglePlayPurchaseRequest(productId, purchaseToken)
                ),
                "Google Play purchase verification failed"
        );
    }

    private QuizApiModels.ActionResponse topUpCurrency(String accessToken, String source, String operationId) throws IOException {
        return executeAction(
                apiService.topUpCurrency(
                        bearer(accessToken),
                        new QuizApiModels.TopUpCurrencyRequest(1000, source, operationId)
                ),
                "Currency top-up failed"
        );
    }

    public QuizApiModels.ActionResponse startQuiz(String accessToken, QuizApiModels.StartQuizRequest request) throws IOException {
        return executeAction(apiService.startQuiz(bearer(accessToken), request), "Starting quiz failed");
    }

    public QuizApiModels.ActionResponse syncOfflineQuiz(String accessToken, QuizApiModels.OfflineQuizSyncRequest request) throws IOException {
        return executeAction(apiService.syncOfflineQuiz(bearer(accessToken), request), "Offline quiz sync failed");
    }

    public QuizApiModels.ActionResponse finishQuiz(String accessToken, String sessionId, QuizApiModels.FinishQuizRequest request) throws IOException {
        return executeAction(apiService.finishQuiz(bearer(accessToken), sessionId, request), "Finishing quiz failed");
    }

    public QuizApiModels.ActionResponse cancelQuiz(String accessToken, String sessionId) throws IOException {
        return executeAction(apiService.cancelQuiz(bearer(accessToken), sessionId), "Cancelling quiz failed");
    }

    public QuizApiModels.ActionResponse purchaseAsset(String accessToken, int assetId) throws IOException {
        return purchaseAsset(accessToken, assetId, null);
    }

    public QuizApiModels.ActionResponse purchaseAsset(
            String accessToken,
            int assetId,
            String operationId
    ) throws IOException {
        QuizApiModels.PurchaseAssetRequest request =
                new QuizApiModels.PurchaseAssetRequest(assetId, operationId);
        return executeAction(apiService.purchaseAsset(bearer(accessToken), assetId, request), "Asset purchase failed");
    }

    public QuizApiModels.ActionResponse selectAsset(String accessToken, int assetId) throws IOException {
        return executeAction(apiService.selectAsset(bearer(accessToken), assetId), "Asset selection failed");
    }

    private QuizApiModels.ActionResponse executeAction(Call<QuizApiModels.ActionResponse> call, String message) throws IOException {
        Response<QuizApiModels.ActionResponse> response = call.execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException(message + ": HTTP " + response.code());
        }
        return response.body();
    }

    private String bearer(String accessToken) {
        return accessToken == null || accessToken.isEmpty() ? null : "Bearer " + accessToken;
    }
}
