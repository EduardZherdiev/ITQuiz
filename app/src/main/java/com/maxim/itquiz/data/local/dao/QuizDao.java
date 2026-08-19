package com.maxim.itquiz.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import com.maxim.itquiz.data.local.entity.OptionEntity;
import com.maxim.itquiz.data.local.entity.OptionTextEntity;
import com.maxim.itquiz.data.local.entity.AssetEntity;
import com.maxim.itquiz.data.local.entity.QuestionEntity;
import com.maxim.itquiz.data.local.entity.QuestionTextEntity;
import com.maxim.itquiz.data.local.entity.QuizSessionEntity;
import com.maxim.itquiz.data.local.entity.CurrencyTransactionEntity;
import com.maxim.itquiz.data.local.entity.TopicEntity;
import com.maxim.itquiz.data.local.entity.TopicTextEntity;
import com.maxim.itquiz.data.local.entity.UserAssetEntity;
import com.maxim.itquiz.data.local.entity.UserEntity;
import com.maxim.itquiz.data.local.entity.OfflineQuizSessionEntity;
import com.maxim.itquiz.data.local.entity.PendingAssetOperationEntity;
import com.maxim.itquiz.data.local.entity.PendingCurrencyOperationEntity;
import com.maxim.itquiz.data.local.model.TopicCardRow;

import java.util.List;

@Dao
public interface QuizDao {

    @Upsert
    void upsertTopics(List<TopicEntity> topics);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTopicTexts(List<TopicTextEntity> topicTexts);

    @Upsert
    void upsertQuestions(List<QuestionEntity> questions);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertQuestionTexts(List<QuestionTextEntity> questionTexts);

    @Upsert
    void upsertOptions(List<OptionEntity> options);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertOptionTexts(List<OptionTextEntity> optionTexts);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAssets(List<AssetEntity> assets);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertUserAssets(List<UserAssetEntity> userAssets);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertUserAsset(UserAssetEntity userAsset);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertCurrencyTransactions(List<CurrencyTransactionEntity> transactions);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertUser(UserEntity user);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSession(QuizSessionEntity session);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSessions(List<QuizSessionEntity> sessions);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertOfflineQuizSession(OfflineQuizSessionEntity session);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertPendingAssetOperation(PendingAssetOperationEntity operation);

    @Query("SELECT * FROM pending_asset_operations ORDER BY created_at")
    List<PendingAssetOperationEntity> getPendingAssetOperations();

    @Query("SELECT COUNT(*) FROM pending_asset_operations")
    int countPendingAssetOperations();

    @Query("DELETE FROM pending_asset_operations WHERE operation_id = :operationId")
    void deletePendingAssetOperation(String operationId);

    @Query("DELETE FROM pending_asset_operations")
    void clearPendingAssetOperations();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertPendingCurrencyOperation(PendingCurrencyOperationEntity operation);

    @Query("SELECT * FROM pending_currency_operations ORDER BY created_at")
    List<PendingCurrencyOperationEntity> getPendingCurrencyOperations();

    @Query("SELECT COUNT(*) FROM pending_currency_operations")
    int countPendingCurrencyOperations();

    @Query("SELECT COUNT(*) FROM pending_currency_operations WHERE user_id = :userId")
    int countPendingCurrencyOperationsForUser(String userId);

    @Query("DELETE FROM pending_currency_operations WHERE operation_id = :operationId")
    void deletePendingCurrencyOperation(String operationId);

    @Query("DELETE FROM pending_currency_operations")
    void clearPendingCurrencyOperations();

    @Query("SELECT * FROM offline_quiz_sessions WHERE id = :sessionId LIMIT 1")
    OfflineQuizSessionEntity getOfflineQuizSession(String sessionId);

    @Query("SELECT * FROM offline_quiz_sessions WHERE state IN ('COMPLETED_PENDING', 'CANCEL_PENDING', 'REMOTE_FINISH_PENDING', 'REMOTE_CANCEL_PENDING') ORDER BY started_at")
    List<OfflineQuizSessionEntity> getPendingOfflineQuizSessions();

    @Query("SELECT * FROM offline_quiz_sessions WHERE state = 'STARTED' ORDER BY started_at")
    List<OfflineQuizSessionEntity> getStartedOfflineQuizSessions();

    @Query("UPDATE offline_quiz_sessions SET remote_session_id = :remoteSessionId WHERE id = :sessionId")
    void setOfflineRemoteSessionId(String sessionId, String remoteSessionId);

    @Query("DELETE FROM offline_quiz_sessions WHERE id = :sessionId")
    void deleteOfflineQuizSession(String sessionId);

    @Query("DELETE FROM offline_quiz_sessions")
    void clearOfflineQuizSessions();

    @Query("UPDATE users SET currency_balance = :balance WHERE id = :userId")
    void updateUserCurrencyBalance(String userId, int balance);

    @Query("DELETE FROM user_assets WHERE user_id = :userId AND asset_id = :assetId")
    void deleteUserAsset(String userId, String assetId);

    @Query("SELECT ua.asset_id FROM user_assets ua INNER JOIN assets a ON a.id = ua.asset_id WHERE ua.user_id = :userId AND a.asset_type = :assetType AND ua.selected = 1 LIMIT 1")
    String getSelectedAssetIdForType(String userId, String assetType);

    @Query("UPDATE users SET display_name = :displayName WHERE id = :userId")
    void updateUserDisplayName(String userId, String displayName);

    @Query("UPDATE users SET last_login_at = :lastLoginAt WHERE id = :userId")
    void updateUserLastLoginAt(String userId, long lastLoginAt);

    @Query("SELECT COUNT(*) FROM topics")
    int countTopics();

    @Query("SELECT COUNT(*) FROM questions")
    int countQuestions();

    @Query("SELECT COUNT(*) FROM options")
    int countOptions();

    @Query("SELECT COUNT(*) FROM users")
    int countUsers();

    @Query("SELECT COUNT(*) FROM assets")
    int countAssets();

    @Query("SELECT COUNT(*) FROM user_assets")
    int countUserAssets();

    @Query("SELECT COUNT(*) FROM currency_transactions")
    int countCurrencyTransactions();

    @Query("SELECT COUNT(*) FROM quiz_sessions")
    int countQuizSessions();

    @Query("SELECT COUNT(*) FROM topic_texts WHERE language_code = :language")
    int countTopicTextsForLanguage(String language);

    @Query("SELECT COUNT(*) FROM question_texts WHERE language_code = :language")
    int countQuestionTextsForLanguage(String language);

    @Query("SELECT COUNT(*) FROM option_texts WHERE language_code = :language")
    int countOptionTextsForLanguage(String language);

    @Query("SELECT t.* FROM topics t WHERE t.is_active = 1 ORDER BY t.code")
    LiveData<List<TopicEntity>> observeActiveTopics();

    @Query("SELECT t.id AS topicId, t.code AS code, t.icon_url AS iconUrl, COALESCE(tt.title, t.code) AS title, COALESCE(tt.description, '') AS description, COALESCE(tt.abbr, t.code) AS abbr FROM topics t LEFT JOIN topic_texts tt ON tt.topic_id = t.id AND tt.language_code = :lang WHERE t.is_active = 1 ORDER BY t.code")
    LiveData<List<TopicCardRow>> observeTopicCards(String lang);

    @Query("SELECT * FROM topics WHERE is_active = 1 ORDER BY code")
    List<TopicEntity> getActiveTopics();

    @Query("SELECT qt.* FROM question_texts qt INNER JOIN questions q ON q.id = qt.question_id WHERE q.topic_id = :topicId AND q.difficulty = :difficulty AND qt.language_code = :lang AND q.is_active = 1 ORDER BY q.id")
    List<QuestionTextEntity> getQuestionTextsByTopicAndDifficulty(String topicId, int difficulty, String lang);

    @Query("SELECT * FROM options WHERE question_id = :questionId")
    List<OptionEntity> getOptionsByQuestionId(String questionId);

    @Query("SELECT * FROM option_texts WHERE option_id = :optionId AND language_code = :lang LIMIT 1")
    OptionTextEntity getOptionText(String optionId, String lang);

    @Query("SELECT * FROM assets")
    List<AssetEntity> getAllAssets();

    @Query("SELECT * FROM assets WHERE id = :assetId LIMIT 1")
    AssetEntity getAssetById(String assetId);

    @Query("SELECT * FROM user_assets WHERE user_id = :userId")
    List<UserAssetEntity> getUserAssets(String userId);

    @Query("UPDATE user_assets SET selected = 0 WHERE user_id = :userId AND asset_id IN (SELECT id FROM assets WHERE asset_type = :assetType)")
    void clearSelectedForUserAndType(String userId, String assetType);

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    UserEntity getUserById(String userId);

    @Query("SELECT qt.* FROM question_texts qt INNER JOIN questions q ON q.id = qt.question_id WHERE q.topic_id = :topicId AND q.difficulty = :difficulty AND qt.language_code = :lang AND q.is_active = 1 ORDER BY q.id")
    LiveData<List<QuestionTextEntity>> observeQuestionTextsByTopicAndDifficulty(String topicId, int difficulty, String lang);

    @Query("SELECT * FROM topic_texts WHERE topic_id = :topicId AND language_code = :lang LIMIT 1")
    TopicTextEntity getTopicText(String topicId, String lang);

    @Query("UPDATE user_assets SET selected = 1 WHERE user_id = :userId AND asset_id = :assetId")
    void selectAsset(String userId, String assetId);

    @Query("DELETE FROM option_texts")
    void clearOptionTexts();

    @Query("DELETE FROM options")
    void clearOptions();

    @Query("DELETE FROM question_texts")
    void clearQuestionTextsForSync();

    @Query("DELETE FROM questions")
    void clearQuestions();

    @Query("DELETE FROM topic_texts")
    void clearTopicTexts();

    @Query("DELETE FROM topic_texts WHERE language_code != :language")
    void deleteTopicTextsExcept(String language);

    @Query("DELETE FROM topic_texts WHERE language_code NOT IN (:languages)")
    void deleteTopicTextsExceptLanguages(List<String> languages);

    @Query("DELETE FROM topic_texts WHERE language_code = :language")
    void clearTopicTextsForLanguage(String language);

    @Query("DELETE FROM question_texts WHERE language_code != :language")
    void deleteQuestionTextsExcept(String language);

    @Query("DELETE FROM question_texts WHERE language_code NOT IN (:languages)")
    void deleteQuestionTextsExceptLanguages(List<String> languages);

    @Query("DELETE FROM question_texts WHERE language_code = :language")
    void clearQuestionTextsForLanguage(String language);

    @Query("DELETE FROM option_texts WHERE language_code != :language")
    void deleteOptionTextsExcept(String language);

    @Query("DELETE FROM option_texts WHERE language_code NOT IN (:languages)")
    void deleteOptionTextsExceptLanguages(List<String> languages);

    @Query("DELETE FROM option_texts WHERE language_code = :language")
    void clearOptionTextsForLanguage(String language);

    @Query("DELETE FROM topics")
    void clearTopics();

    @Query("DELETE FROM user_assets")
    void clearUserAssets();

    @Query("DELETE FROM currency_transactions")
    void clearCurrencyTransactions();

    @Query("DELETE FROM quiz_sessions")
    void clearQuizSessions();

    @Query("DELETE FROM assets")
    void clearAssets();

    @Query("DELETE FROM users")
    void clearUsers();

    @Transaction
    @Query("DELETE FROM question_texts")
    void clearQuestionTexts();
}
