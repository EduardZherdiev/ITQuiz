package com.maxim.itquiz.data.remote.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class BootstrapDto {
    @SerializedName("server_revision")
    public int serverRevision;
    public List<TopicDto> topics;
    @SerializedName("topic_texts")
    public List<TopicTextDto> topicTexts;
    public List<QuestionDto> questions;
    @SerializedName("question_texts")
    public List<QuestionTextDto> questionTexts;
    public List<OptionDto> options;
    @SerializedName("option_texts")
    public List<OptionTextDto> optionTexts;
    public List<AssetDto> assets;
    @SerializedName("user_assets")
    public List<UserAssetDto> userAssets;
    public List<UserDto> users;
    @SerializedName("currency_transactions")
    public List<CurrencyTransactionDto> currencyTransactions;
    @SerializedName("quiz_sessions")
    public List<QuizSessionDto> quizSessions;

    public static class TopicDto {
        public int id;
        public String code;
        @SerializedName("icon_url")
        public String iconUrl;
        @SerializedName("created_at")
        public long createdAt;
        @SerializedName("updated_at")
        public long updatedAt;
        @SerializedName("plays_count")
        public int playsCount;
        @SerializedName("likes_count")
        public int likesCount;
        @SerializedName("views_count")
        public int viewsCount;
        @SerializedName("author_user_id")
        public String authorUserId;
        @SerializedName("is_public")
        public boolean isPublic;
        @SerializedName("is_active")
        public boolean isActive;
    }

    public static class TopicTextDto {
        @SerializedName("topic_id")
        public int topicId;
        @SerializedName("language_code")
        public String languageCode;
        public String title;
        public String description;
        public String abbr;
    }

    public static class QuestionDto {
        public int id;
        @SerializedName("topic_id")
        public int topicId;
        public String difficulty;
        @SerializedName("is_active")
        public boolean isActive;
    }

    public static class QuestionTextDto {
        @SerializedName("question_id")
        public int questionId;
        @SerializedName("language_code")
        public String languageCode;
        @SerializedName("question_text")
        public String questionText;
        public String explanation;
    }

    public static class OptionDto {
        public int id;
        @SerializedName("question_id")
        public int questionId;
        @SerializedName("is_correct")
        public boolean isCorrect;
    }

    public static class OptionTextDto {
        @SerializedName("option_id")
        public int optionId;
        @SerializedName("language_code")
        public String languageCode;
        @SerializedName("option_text")
        public String optionText;
    }

    public static class AssetDto {
        public int id;
        @SerializedName("asset_type")
        public String assetType;
        @SerializedName("asset_code")
        public String assetCode;
        public int price;
        @SerializedName("is_active")
        public boolean isActive;
    }

    public static class UserDto {
        public String id;
        @SerializedName("google_uid")
        public String googleUid;
        public String email;
        @SerializedName("display_name")
        public String displayName;
        @SerializedName("photo_url")
        public String photoUrl;
        @SerializedName("currency_balance")
        public int currencyBalance;
        @SerializedName("last_login_at")
        public Long lastLoginAt;
    }

    public static class UserAssetDto {
        @SerializedName("user_id")
        public String userId;
        @SerializedName("asset_id")
        public int assetId;
        public boolean selected;
        @SerializedName("purchased_at")
        public Long purchasedAt;
    }

    public static class CurrencyTransactionDto {
        public int id;
        @SerializedName("user_id")
        public String userId;
        public int amount;
        public String reason;
        @SerializedName("created_at")
        public long createdAt;
    }

    public static class QuizSessionDto {
        public int id;
        @SerializedName("user_id")
        public String userId;
        @SerializedName("topic_id")
        public int topicId;
        public String mode;
        public String difficulty;
        @SerializedName("total_questions")
        public int totalQuestions;
        @SerializedName("correct_answers")
        public int correctAnswers;
        public int stake;
        @SerializedName("reward_amount")
        public int rewardAmount;
        @SerializedName("started_at")
        public long startedAt;
        @SerializedName("finished_at")
        public Long finishedAt;
    }
}
