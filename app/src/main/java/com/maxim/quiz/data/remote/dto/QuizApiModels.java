package com.maxim.quiz.data.remote.dto;

import com.google.gson.annotations.SerializedName;

public final class QuizApiModels {
    private QuizApiModels() {
    }

    public static class AuthRequest {
        @SerializedName("device_id")
        public String deviceId;

        public AuthRequest(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    public static class AuthResponse {
        @SerializedName("user_id")
        public String userId;
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("expires_at")
        public long expiresAt;
    }

    public static class StartQuizRequest {
        @SerializedName("topic_id")
        public int topicId;
        public String mode;
        public String difficulty;
        public int stake;
        @SerializedName("total_questions")
        public int totalQuestions;
        @SerializedName("client_session_id")
        public String clientSessionId;
    }

    public static class FinishQuizRequest {
        @SerializedName("correct_answers")
        public int correctAnswers;
        @SerializedName("total_questions")
        public int totalQuestions;
    }

    public static class OfflineQuizSyncRequest {
        @SerializedName("client_session_id")
        public String clientSessionId;
        @SerializedName("topic_id")
        public int topicId;
        public String mode;
        public String difficulty;
        public int stake;
        @SerializedName("total_questions")
        public int totalQuestions;
        @SerializedName("correct_answers")
        public int correctAnswers;
        public boolean cancelled;
    }

    public static class PurchaseAssetRequest {
        @SerializedName("asset_id")
        public int assetId;
    }

    public static class TopUpCurrencyRequest {
        public int amount;
        public String source;
        @SerializedName("operation_id")
        public String operationId;

        public TopUpCurrencyRequest(int amount, String source, String operationId) {
            this.amount = amount;
            this.source = source;
            this.operationId = operationId;
        }
    }

    public static class GooglePlayPurchaseRequest {
        @SerializedName("product_id")
        public String productId;
        @SerializedName("purchase_token")
        public String purchaseToken;

        public GooglePlayPurchaseRequest(String productId, String purchaseToken) {
            this.productId = productId;
            this.purchaseToken = purchaseToken;
        }
    }

    public static class ActionResponse {
        @SerializedName("session_id")
        public Long sessionId;
        @SerializedName("asset_id")
        public Integer assetId;
        public int balance;
        public int stake;
        @SerializedName("reward_amount")
        public int rewardAmount;
        @SerializedName("finished_at")
        public Long finishedAt;
    }
}
