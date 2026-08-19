package com.maxim.quiz.data.remote;

import com.maxim.quiz.data.remote.dto.BootstrapDto;
import com.maxim.quiz.data.remote.dto.QuizApiModels;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface QuizApiService {
    @POST("api/v1/auth/anonymous")
    Call<QuizApiModels.AuthResponse> authenticate(@Body QuizApiModels.AuthRequest request);

    @POST("api/v1/auth/play-games/link")
    Call<QuizApiModels.AuthResponse> linkPlayGamesAccount(
            @Header("Authorization") String authorization,
            @Body QuizApiModels.PlayGamesLinkRequest request
    );

    @GET("api/v1/bootstrap")
    Call<BootstrapDto> getBootstrap(
            @Header("Authorization") String authorization,
            @Header("Accept-Language") String language
    );

    @POST("api/v1/me/currency/top-up")
    Call<QuizApiModels.ActionResponse> topUpCurrency(
            @Header("Authorization") String authorization,
            @Body QuizApiModels.TopUpCurrencyRequest request
    );

    @POST("api/v1/me/store/google-play/verify")
    Call<QuizApiModels.ActionResponse> verifyGooglePlayPurchase(
            @Header("Authorization") String authorization,
            @Body QuizApiModels.GooglePlayPurchaseRequest request
    );

    @GET("api/v1/me/balance")
    Call<QuizApiModels.ActionResponse> getBalance(
            @Header("Authorization") String authorization
    );

    @POST("api/v1/me/reset")
    Call<QuizApiModels.ActionResponse> resetGameData(
            @Header("Authorization") String authorization
    );

    @POST("api/v1/me/quiz-sessions/start")
    Call<QuizApiModels.ActionResponse> startQuiz(
            @Header("Authorization") String authorization,
            @Body QuizApiModels.StartQuizRequest request
    );

    @POST("api/v1/me/quiz-sessions/offline-sync")
    Call<QuizApiModels.ActionResponse> syncOfflineQuiz(
            @Header("Authorization") String authorization,
            @Body QuizApiModels.OfflineQuizSyncRequest request
    );

    @POST("api/v1/me/quiz-sessions/{sessionId}/finish")
    Call<QuizApiModels.ActionResponse> finishQuiz(
            @Header("Authorization") String authorization,
            @Path("sessionId") String sessionId,
            @Body QuizApiModels.FinishQuizRequest request
    );

    @POST("api/v1/me/quiz-sessions/{sessionId}/cancel")
    Call<QuizApiModels.ActionResponse> cancelQuiz(
            @Header("Authorization") String authorization,
            @Path("sessionId") String sessionId
    );

    @POST("api/v1/me/assets/{assetId}/purchase")
    Call<QuizApiModels.ActionResponse> purchaseAsset(
            @Header("Authorization") String authorization,
            @Path("assetId") int assetId,
            @Body QuizApiModels.PurchaseAssetRequest request
    );

    @POST("api/v1/me/assets/{assetId}/select")
    Call<QuizApiModels.ActionResponse> selectAsset(
            @Header("Authorization") String authorization,
            @Path("assetId") int assetId
    );
}
