package com.maxim.quiz.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.maxim.quiz.NetworkState;
import com.maxim.quiz.QuizApplication;

import androidx.lifecycle.LiveData;

import com.maxim.quiz.data.DifficultyLevel;
import com.maxim.quiz.data.local.QuizDatabase;
import com.maxim.quiz.data.local.dao.QuizDao;
import com.maxim.quiz.data.local.entity.AssetEntity;
import com.maxim.quiz.data.local.entity.CurrencyTransactionEntity;
import com.maxim.quiz.data.local.entity.OptionEntity;
import com.maxim.quiz.data.local.entity.OptionTextEntity;
import com.maxim.quiz.data.local.entity.QuestionEntity;
import com.maxim.quiz.data.local.entity.QuestionTextEntity;
import com.maxim.quiz.data.local.entity.QuizSessionEntity;
import com.maxim.quiz.data.local.entity.TopicEntity;
import com.maxim.quiz.data.local.entity.TopicTextEntity;
import com.maxim.quiz.data.local.entity.UserAssetEntity;
import com.maxim.quiz.data.local.entity.UserEntity;
import com.maxim.quiz.data.local.entity.OfflineQuizSessionEntity;
import com.maxim.quiz.data.local.entity.PendingAssetOperationEntity;
import com.maxim.quiz.data.local.model.TopicCardRow;
import com.maxim.quiz.data.remote.QuizRemoteDataSource;
import com.maxim.quiz.data.remote.dto.BootstrapDto;
import com.maxim.quiz.data.remote.dto.QuizApiModels;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.UUID;
import java.io.IOException;

public class QuizRepository {

    private static final long NETWORK_OPERATION_BUDGET_MS = 5000L;
    private static final long BOOTSTRAP_DOWNLOAD_BUDGET_MS = 20000L;
    private static final int MAX_NETWORK_ATTEMPTS = 2;

    private static final String PREF_AUTH_TOKEN = "pref_auth_token";
    private static final String PREF_USER_ID = "pref_user_id";
    private static final String PREF_DEVICE_ID = "pref_device_id";
    private static final Object SYNC_LOCK = new Object();
    private static final ReentrantLock BOOTSTRAP_DATA_LOCK = new ReentrantLock(true);
    private static final ReentrantLock OFFLINE_SYNC_LOCK = new ReentrantLock(true);
    private static final ReentrantLock ASSET_SYNC_LOCK = new ReentrantLock(true);
    private static final AtomicBoolean TOP_UP_IN_PROGRESS = new AtomicBoolean(false);
    private static final Queue<SyncRequest> SYNC_QUEUE = new ArrayDeque<>();
    private static final List<SyncCallback> ACTIVE_SYNC_CALLBACKS = new ArrayList<>();
    private static boolean syncInProgress;
    private static String activeSyncLanguage;

    public interface SyncCallback {
        void onSuccess();

        void onError(Throwable throwable);
    }

    public enum BootstrapStage {
        CONNECTING,
        TOPICS_READY,
        ASSETS_READY,
        QUESTIONS_READY
    }

    public interface BootstrapFlowCallback extends SyncCallback {
        void onStageChanged(BootstrapStage stage, String message);

        void awaitNext() throws InterruptedException;
    }

    public static final class AssetPurchaseResult {
        public final int balance;
        public final boolean queuedForSync;

        public AssetPurchaseResult(int balance, boolean queuedForSync) {
            this.balance = balance;
            this.queuedForSync = queuedForSync;
        }
    }

    private final QuizDatabase quizDatabase;
    private final QuizDao quizDao;
    private final QuizRemoteDataSource remoteDataSource;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Context appContext;

    public static QuizRepository create(Context context) {
        Context appContext = context.getApplicationContext();
        QuizDatabase database = QuizDatabase.getInstance(appContext);
        return new QuizRepository(database, database.quizDao(), QuizRemoteDataSource.create(), appContext);
    }

    public QuizRepository(QuizDao quizDao) {
        this(null, quizDao, null, null);
    }

    public QuizRepository(QuizDatabase quizDatabase, QuizRemoteDataSource remoteDataSource) {
        this(quizDatabase, quizDatabase.quizDao(), remoteDataSource, null);
    }

    private QuizRepository(QuizDatabase quizDatabase, QuizDao quizDao, QuizRemoteDataSource remoteDataSource, Context appContext) {
        this.quizDatabase = quizDatabase;
        this.quizDao = quizDao;
        this.remoteDataSource = remoteDataSource;
        this.appContext = appContext;
    }

    public LiveData<List<TopicEntity>> observeTopics() {
        return quizDao.observeActiveTopics();
    }

    public LiveData<List<TopicCardRow>> observeTopicCards(String lang) {
        return quizDao.observeTopicCards(lang);
    }

    public LiveData<List<QuestionTextEntity>> observeQuestionTextsByTopic(String topicId, int difficulty, String lang) {
        return quizDao.observeQuestionTextsByTopicAndDifficulty(topicId, difficulty, lang);
    }

    public void clearQuestionTextsAsync() {
        ioExecutor.execute(quizDao::clearQuestionTexts);
    }

    public void syncBootstrapAsync(String lang) {
        syncBootstrapAsync(lang, null);
    }

    /** Synchronous catalog download used by the settings language gate. */
    public void syncBootstrapBlocking(String language) throws Exception {
        if (remoteDataSource == null || quizDatabase == null || appContext == null) {
            throw new IllegalStateException("Repository was created without remote sync support");
        }
        if (!NetworkState.isAvailable()) {
            throw new IOException("Network is unavailable");
        }
        BOOTSTRAP_DATA_LOCK.lock();
        try {
            syncOfflineSessionsBlocking();
            syncPendingAssetOperationsBlocking();
            BootstrapBundle bundle = fetchBootstrapBundle(language);
            quizDatabase.runInTransaction(() -> prepareLanguageCache(bundle.languages));
            quizDatabase.runInTransaction(() -> {
                saveBootstrapTopics(bundle.primary);
                if (bundle.english != null && bundle.english != bundle.primary) {
                    saveBootstrapTopics(bundle.english);
                }
                saveBootstrapAssets(bundle.primary);
                saveBootstrapQuestions(bundle.primary);
                if (bundle.english != null && bundle.english != bundle.primary) {
                    saveBootstrapQuestions(bundle.english);
                }
            });
            logLanguageCacheState(bundle.languages, "blocking-bootstrap");
        } finally {
            BOOTSTRAP_DATA_LOCK.unlock();
        }
    }

    public boolean isLanguageCached(String language) {
        if (quizDatabase == null) {
            return false;
        }
        String normalized = normalizeLanguage(language);
        return quizDao.countTopicTextsForLanguage(normalized) > 0
                && quizDao.countQuestionTextsForLanguage(normalized) > 0
                && quizDao.countOptionTextsForLanguage(normalized) > 0;
    }

    public boolean isBootstrapSyncInProgress(String lang) {
        String requestedLanguage = lang == null || lang.isEmpty() ? "en" : lang;
        synchronized (SYNC_LOCK) {
            return syncInProgress && requestedLanguage.equals(activeSyncLanguage);
        }
    }

    public void syncBootstrapAsync(String lang, SyncCallback callback) {
        if (remoteDataSource == null || quizDatabase == null) {
            if (callback != null) {
                callback.onError(new IllegalStateException("Repository was created without remote sync support"));
            }
            return;
        }

        SyncRequest request = new SyncRequest(this, normalizeLanguage(lang), callback);
        synchronized (SYNC_LOCK) {
            if (syncInProgress) {
                if (request.language.equals(activeSyncLanguage)) {
                    if (callback != null) {
                        ACTIVE_SYNC_CALLBACKS.add(callback);
                    }
                } else {
                    SYNC_QUEUE.add(request);
                }
                return;
            }
            syncInProgress = true;
            activeSyncLanguage = request.language;
            ACTIVE_SYNC_CALLBACKS.clear();
            if (callback != null) {
                ACTIVE_SYNC_CALLBACKS.add(callback);
            }
        }
        startBootstrapSync(request);
    }

    public void syncBootstrapAsync(String lang, BootstrapFlowCallback callback) {
        if (remoteDataSource == null || quizDatabase == null) {
            if (callback != null) {
                callback.onError(new IllegalStateException("Repository was created without remote sync support"));
            }
            return;
        }

        SyncRequest request = new SyncRequest(this, normalizeLanguage(lang), callback);
        synchronized (SYNC_LOCK) {
            if (syncInProgress) {
                if (request.language.equals(activeSyncLanguage)) {
                    if (callback != null) {
                        ACTIVE_SYNC_CALLBACKS.add(callback);
                    }
                } else {
                    SYNC_QUEUE.add(request);
                }
                return;
            }
            syncInProgress = true;
            activeSyncLanguage = request.language;
            ACTIVE_SYNC_CALLBACKS.clear();
            if (callback != null) {
                ACTIVE_SYNC_CALLBACKS.add(callback);
            }
        }
        startBootstrapSync(request);
    }

    /** Starts delivery of finished/cancelled offline games when connectivity returns. */
    public void syncOfflineSessionsAsync() {
        if (remoteDataSource == null || quizDatabase == null || appContext == null || !NetworkState.isAvailable()) {
            return;
        }
        ioExecutor.execute(() -> {
            if (!OFFLINE_SYNC_LOCK.tryLock()) {
                return;
            }
            try {
                syncOfflineSessions();
            } catch (Exception error) {
                android.util.Log.w("QuizRepository", "Offline session sync will retry", error);
            } finally {
                OFFLINE_SYNC_LOCK.unlock();
            }
        });
    }

    /** Flushes the outbox before another server-side balance operation starts. */
    public void syncOfflineSessionsBlocking() throws Exception {
        if (remoteDataSource == null || quizDatabase == null || appContext == null) {
            return;
        }
        OFFLINE_SYNC_LOCK.lock();
        try {
            if (!NetworkState.isAvailable()) {
                List<OfflineQuizSessionEntity> pending = quizDao.getPendingOfflineQuizSessions();
                if (pending != null && !pending.isEmpty()) {
                    throw new IOException("Pending offline results cannot be synchronized while offline");
                }
                return;
            }
            syncOfflineSessions();
        } finally {
            OFFLINE_SYNC_LOCK.unlock();
        }
    }

    /** Flushes locally completed asset actions when the connection returns. */
    public void syncPendingAssetOperationsAsync() {
        syncPendingAssetOperationsAsync(null);
    }

    public void syncPendingAssetOperationsAsync(SyncCallback callback) {
        if (remoteDataSource == null || quizDatabase == null || appContext == null || !NetworkState.isAvailable()) {
            if (callback != null) {
                callback.onError(new IOException("Network is unavailable"));
            }
            return;
        }
        ioExecutor.execute(() -> {
            if (!ASSET_SYNC_LOCK.tryLock()) {
                if (callback != null) {
                    callback.onError(new IOException("Asset sync is already in progress"));
                }
                return;
            }
            try {
                syncPendingAssetOperations();
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                android.util.Log.w("QuizRepository", "Asset operation sync will retry", error);
                if (callback != null) {
                    callback.onError(error);
                }
            } finally {
                ASSET_SYNC_LOCK.unlock();
            }
        });
    }

    public void syncPendingAssetOperationsBlocking() throws Exception {
        if (remoteDataSource == null || quizDatabase == null || appContext == null) {
            return;
        }
        ASSET_SYNC_LOCK.lock();
        try {
            if (!NetworkState.isAvailable()) {
                if (quizDao.countPendingAssetOperations() > 0) {
                    throw new IOException("Pending asset actions cannot be synchronized while offline");
                }
                return;
            }
            syncPendingAssetOperations();
        } finally {
            ASSET_SYNC_LOCK.unlock();
        }
    }

    private void startBootstrapSync(SyncRequest request) {
        request.repository.ioExecutor.execute(() -> {
            Throwable failure = null;
            BOOTSTRAP_DATA_LOCK.lock();
            try {
                // A server refresh must settle local actions first. Otherwise
                // an old bootstrap response could overwrite a just-created
                // offline purchase with the previous ownership and balance.
                request.repository.syncOfflineSessionsBlocking();
                request.repository.syncPendingAssetOperationsBlocking();
                if (request.flowCallback != null) {
                    request.flowCallback.onStageChanged(BootstrapStage.CONNECTING, "connecting");
                    request.flowCallback.awaitNext();
                }
                BootstrapBundle bundle = request.repository.fetchBootstrapBundle(request.language);
                final BootstrapBundle syncedBundle = bundle;
                request.repository.quizDatabase.runInTransaction(
                        () -> request.repository.prepareLanguageCache(syncedBundle.languages)
                );
                request.repository.quizDatabase.runInTransaction(() -> {
                    request.repository.saveBootstrapTopics(syncedBundle.primary);
                    if (syncedBundle.english != null && syncedBundle.english != syncedBundle.primary) {
                        request.repository.saveBootstrapTopics(syncedBundle.english);
                    }
                });
                if (request.flowCallback != null) {
                    request.flowCallback.onStageChanged(BootstrapStage.TOPICS_READY, "topics ready");
                    request.flowCallback.awaitNext();
                }
                request.repository.quizDatabase.runInTransaction(
                        () -> request.repository.saveBootstrapAssets(syncedBundle.primary)
                );
                if (request.flowCallback != null) {
                    request.flowCallback.onStageChanged(BootstrapStage.ASSETS_READY, "assets ready");
                    request.flowCallback.awaitNext();
                }
                request.repository.quizDatabase.runInTransaction(() -> {
                    request.repository.saveBootstrapQuestions(syncedBundle.primary);
                    if (syncedBundle.english != null && syncedBundle.english != syncedBundle.primary) {
                        request.repository.saveBootstrapQuestions(syncedBundle.english);
                    }
                });
                request.repository.logLanguageCacheState(syncedBundle.languages, "async-bootstrap");
                if (request.flowCallback != null) {
                    request.flowCallback.onStageChanged(BootstrapStage.QUESTIONS_READY, "questions ready");
                    request.flowCallback.awaitNext();
                }
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                BOOTSTRAP_DATA_LOCK.unlock();
            }
            completeBootstrapSync(failure);
        });
    }

    /** Downloads the requested language and English in parallel. */
    private BootstrapBundle fetchBootstrapBundle(String language) throws Exception {
        String normalized = normalizeLanguage(language);
        if ("en".equals(normalized)) {
            BootstrapDto english = fetchBootstrapWithRetries("en");
            return new BootstrapBundle(english, english, Arrays.asList("en"));
        }

        // Authenticate once before starting the two parallel downloads. This
        // avoids two anonymous users being created for the same device during
        // the first dual-language bootstrap.
        authenticateIfNeeded();
        ExecutorService parallel = Executors.newFixedThreadPool(2);
        try {
            // English is retained locally after the first bootstrap. Download
            // only the missing language on later switches so a cold Render
            // wake-up does not unnecessarily double the request time.
            if (isLanguageCached("en")) {
                BootstrapDto primary = fetchBootstrapWithRetries(
                        normalized, BOOTSTRAP_DOWNLOAD_BUDGET_MS
                );
                return new BootstrapBundle(primary, null, Arrays.asList(normalized));
            }
            Future<BootstrapDto> primaryFuture = parallel.submit(
                    () -> fetchBootstrapWithRetries(normalized, BOOTSTRAP_DOWNLOAD_BUDGET_MS)
            );
            Future<BootstrapDto> englishFuture = parallel.submit(
                    () -> fetchBootstrapWithRetries("en", BOOTSTRAP_DOWNLOAD_BUDGET_MS)
            );
            long deadline = System.nanoTime() + BOOTSTRAP_DOWNLOAD_BUDGET_MS * 1_000_000L;
            BootstrapDto primary = primaryFuture.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            BootstrapDto english = englishFuture.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            return new BootstrapBundle(primary, english, Arrays.asList(normalized, "en"));
        } finally {
            parallel.shutdownNow();
        }
    }

    private long remainingMillis(long deadlineNanos) throws IOException {
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) {
            throw new IOException("Server check timed out");
        }
        return remaining;
    }

    private String normalizeLanguage(String language) {
        if ("ru".equalsIgnoreCase(language)) {
            return "ru";
        }
        if ("uk".equalsIgnoreCase(language)) {
            return "uk";
        }
        return "en";
    }

    private static final class BootstrapBundle {
        final BootstrapDto primary;
        final BootstrapDto english;
        final List<String> languages;

        BootstrapBundle(BootstrapDto primary, BootstrapDto english, List<String> languages) {
            this.primary = primary;
            this.english = english;
            this.languages = languages;
        }
    }

    private BootstrapDto fetchBootstrapWithRetries(String language) throws Exception {
        return fetchBootstrapWithRetries(language, NETWORK_OPERATION_BUDGET_MS);
    }

    private BootstrapDto fetchBootstrapWithRetries(String language, long budgetMs) throws Exception {
        Exception lastError = null;
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        for (int attempt = 0; attempt < MAX_NETWORK_ATTEMPTS; attempt++) {
            try {
                String token = authenticateIfNeeded();
                try {
                    return remoteDataSource.fetchBootstrap(token, language);
                } catch (IOException unauthorized) {
                    if (unauthorized.getMessage() == null || !unauthorized.getMessage().contains("401")) {
                        throw unauthorized;
                    }
                    clearStoredAuth();
                    return remoteDataSource.fetchBootstrap(authenticateIfNeeded(), language);
                }
            } catch (Exception error) {
                lastError = error;
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (attempt == MAX_NETWORK_ATTEMPTS - 1 || remainingMs <= 0
                        || (!NetworkState.isAvailable() && attempt > 0)) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(200L, Math.max(1L, remainingMs)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Network is unavailable");
    }

    private void completeBootstrapSync(Throwable failure) {
        List<SyncCallback> callbacks;
        SyncRequest next = null;
        synchronized (SYNC_LOCK) {
            callbacks = new ArrayList<>(ACTIVE_SYNC_CALLBACKS);
            ACTIVE_SYNC_CALLBACKS.clear();
            syncInProgress = false;
            activeSyncLanguage = null;
            if (!SYNC_QUEUE.isEmpty()) {
                next = SYNC_QUEUE.remove();
                syncInProgress = true;
                activeSyncLanguage = next.language;
                if (next.callback != null) {
                    ACTIVE_SYNC_CALLBACKS.add(next.callback);
                }
            }
        }

        for (SyncCallback callback : callbacks) {
            if (failure == null) {
                callback.onSuccess();
            } else {
                callback.onError(failure);
            }
        }
        if (next != null) {
            startBootstrapSync(next);
        }
    }

    private static final class SyncRequest {
        final QuizRepository repository;
        final String language;
        final SyncCallback callback;
        final BootstrapFlowCallback flowCallback;

        SyncRequest(QuizRepository repository, String language, SyncCallback callback) {
            this.repository = repository;
            this.language = language;
            this.callback = callback;
            this.flowCallback = callback instanceof BootstrapFlowCallback ? (BootstrapFlowCallback) callback : null;
        }
    }

    public String getAccessToken() {
        if (appContext == null) {
            return null;
        }
        return prefs().getString(PREF_AUTH_TOKEN, null);
    }

    public String getUserId() {
        if (appContext == null) {
            return "user_test";
        }
        return prefs().getString(PREF_USER_ID, "user_test");
    }

    public QuizApiModels.ActionResponse startQuiz(QuizApiModels.StartQuizRequest request) throws Exception {
        return withAuthenticatedRetries(token -> remoteDataSource.startQuiz(token, request));
    }

    public boolean hasLocalQuestions(String topicId, int difficulty, String lang) {
        if (quizDatabase == null || topicId == null || topicId.trim().isEmpty()) {
            return false;
        }
        List<QuestionTextEntity> cached = quizDao.getQuestionTextsByTopicAndDifficulty(topicId, difficulty, lang);
        if (cached != null && !cached.isEmpty()) {
            return true;
        }
        if (!"en".equals(lang)) {
            cached = quizDao.getQuestionTextsByTopicAndDifficulty(topicId, difficulty, "en");
        }
        return cached != null && !cached.isEmpty();
    }

    /** Creates an immediately playable local session and reserves the stake locally. */
    public String startOfflineQuiz(QuizApiModels.StartQuizRequest request) throws Exception {
        if (quizDatabase == null || appContext == null) {
            throw new IllegalStateException("Local database is unavailable");
        }
        int balance = QuizApplication.getCurrencyBalance(appContext);
        if (balance < request.stake) {
            throw new IllegalStateException("Not enough coins");
        }

        String sessionId = request.clientSessionId;
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = "offline_" + UUID.randomUUID();
            request.clientSessionId = sessionId;
        }
        OfflineQuizSessionEntity session = new OfflineQuizSessionEntity();
        session.id = sessionId;
        session.userId = getUserId();
        session.topicId = String.valueOf(request.topicId);
        session.mode = request.mode == null ? "solo" : request.mode;
        session.difficulty = request.difficulty == null ? "common" : request.difficulty;
        session.totalQuestions = request.totalQuestions;
        session.stake = request.stake;
        session.correctAnswers = 0;
        session.rewardAmount = 0;
        session.state = "STARTED";
        session.startedAt = System.currentTimeMillis();
        session.finishedAt = 0L;

        int updatedBalance = balance - request.stake;
        quizDatabase.runInTransaction(() -> {
            quizDao.upsertOfflineQuizSession(session);
            quizDao.updateUserCurrencyBalance(getUserId(), updatedBalance);
        });
        QuizApplication.setDisplayedCurrencyBalance(appContext, updatedBalance);
        return sessionId;
    }

    public QuizApiModels.ActionResponse finishQuiz(String sessionId, QuizApiModels.FinishQuizRequest request) throws Exception {
        if (isOfflineSessionId(sessionId)) {
            return finishOfflineQuiz(sessionId, request);
        }
        return withAuthenticatedRetries(token -> remoteDataSource.finishQuiz(token, sessionId, request));
    }

    public QuizApiModels.ActionResponse cancelQuiz(String sessionId) throws Exception {
        if (isOfflineSessionId(sessionId)) {
            return cancelOfflineQuiz(sessionId);
        }
        return withAuthenticatedRetries(token -> remoteDataSource.cancelQuiz(token, sessionId));
    }

    public void queueRemoteFinish(String remoteSessionId, String topicId, String mode, String difficulty,
                                   int stake, int totalQuestions, int correctAnswers) {
        if (quizDatabase == null || appContext == null || remoteSessionId == null || remoteSessionId.isEmpty()) {
            return;
        }
        OfflineQuizSessionEntity pending = new OfflineQuizSessionEntity();
        pending.id = "remote_finish_" + remoteSessionId;
        pending.userId = getUserId();
        pending.topicId = topicId == null ? "0" : topicId;
        pending.mode = mode == null ? "solo" : mode;
        pending.difficulty = difficulty == null ? "common" : difficulty;
        pending.totalQuestions = totalQuestions;
        pending.stake = stake;
        pending.correctAnswers = correctAnswers;
        pending.rewardAmount = 0;
        pending.state = "REMOTE_FINISH_PENDING";
        pending.remoteSessionId = remoteSessionId;
        pending.startedAt = System.currentTimeMillis();
        pending.finishedAt = pending.startedAt;
        quizDao.upsertOfflineQuizSession(pending);
    }

    public void queueRemoteCancel(String remoteSessionId, String topicId, String mode, String difficulty,
                                   int stake, int totalQuestions) {
        if (quizDatabase == null || appContext == null || remoteSessionId == null || remoteSessionId.isEmpty()) {
            return;
        }
        OfflineQuizSessionEntity pending = new OfflineQuizSessionEntity();
        pending.id = "remote_cancel_" + remoteSessionId;
        pending.userId = getUserId();
        pending.topicId = topicId == null ? "0" : topicId;
        pending.mode = mode == null ? "solo" : mode;
        pending.difficulty = difficulty == null ? "common" : difficulty;
        pending.totalQuestions = totalQuestions;
        pending.stake = stake;
        pending.correctAnswers = 0;
        pending.rewardAmount = 0;
        pending.state = "REMOTE_CANCEL_PENDING";
        pending.remoteSessionId = remoteSessionId;
        pending.startedAt = System.currentTimeMillis();
        pending.finishedAt = pending.startedAt;
        quizDao.upsertOfflineQuizSession(pending);
    }

    public boolean isOfflineSessionId(String sessionId) {
        return sessionId != null && sessionId.startsWith("offline_");
    }

    private QuizApiModels.ActionResponse finishOfflineQuiz(String sessionId, QuizApiModels.FinishQuizRequest request) {
        OfflineQuizSessionEntity session = quizDao.getOfflineQuizSession(sessionId);
        if (session == null) {
            throw new IllegalStateException("Offline quiz session not found");
        }
        if ("COMPLETED_PENDING".equals(session.state)) {
            QuizApiModels.ActionResponse alreadyFinished = new QuizApiModels.ActionResponse();
            alreadyFinished.balance = QuizApplication.getCurrencyBalance(appContext);
            alreadyFinished.stake = session.stake;
            alreadyFinished.rewardAmount = session.rewardAmount;
            return alreadyFinished;
        }
        int correct = Math.max(0, Math.min(request.correctAnswers, session.totalQuestions));
        int reward = calculateReward(session.stake, session.difficulty, correct, session.totalQuestions);
        int balance = QuizApplication.getCurrencyBalance(appContext) + reward;
        session.correctAnswers = correct;
        session.rewardAmount = reward;
        session.finishedAt = System.currentTimeMillis();
        session.state = "COMPLETED_PENDING";
        quizDatabase.runInTransaction(() -> {
            quizDao.upsertOfflineQuizSession(session);
            quizDao.updateUserCurrencyBalance(getUserId(), balance);
        });
        QuizApplication.setDisplayedCurrencyBalance(appContext, balance);
        if (NetworkState.isAvailable()) {
            syncOfflineSessionsAsync();
        }
        QuizApiModels.ActionResponse response = new QuizApiModels.ActionResponse();
        response.balance = balance;
        response.stake = session.stake;
        response.rewardAmount = reward;
        return response;
    }

    private QuizApiModels.ActionResponse cancelOfflineQuiz(String sessionId) {
        OfflineQuizSessionEntity session = quizDao.getOfflineQuizSession(sessionId);
        if (session == null) {
            return new QuizApiModels.ActionResponse();
        }
        session.finishedAt = System.currentTimeMillis();
        session.rewardAmount = 0;
        session.state = "CANCEL_PENDING";
        quizDao.upsertOfflineQuizSession(session);
        if (NetworkState.isAvailable()) {
            syncOfflineSessionsAsync();
        }
        QuizApiModels.ActionResponse response = new QuizApiModels.ActionResponse();
        response.balance = QuizApplication.getCurrencyBalance(appContext);
        response.stake = session.stake;
        return response;
    }

    private int calculateReward(int stake, String difficulty, int correct, int total) {
        if (total <= 0) {
            return 0;
        }
        double multiplier = "basic".equalsIgnoreCase(difficulty) ? 1.5d
                : "advanced".equalsIgnoreCase(difficulty) ? 3.0d : 2.0d;
        int percent = Math.round(correct * 100f / total);
        return (int) Math.round(stake * multiplier * percent / 100d);
    }

    private void syncOfflineSessions() throws Exception {
        if (!NetworkState.isAvailable()) {
            throw new IOException("Network is unavailable");
        }
        List<OfflineQuizSessionEntity> pending = quizDao.getPendingOfflineQuizSessions();
        android.util.Log.i("QuizRepository", "offline-sync: pending="
                + (pending == null ? 0 : pending.size())
                + ", localBalanceBefore=" + QuizApplication.getCurrencyBalance(appContext));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (OfflineQuizSessionEntity session : pending) {
            if (!NetworkState.isAvailable()) {
                throw new IOException("Network is unavailable");
            }
            QuizApiModels.ActionResponse response;
            if ("REMOTE_FINISH_PENDING".equals(session.state)) {
                QuizApiModels.FinishQuizRequest finish = new QuizApiModels.FinishQuizRequest();
                finish.correctAnswers = session.correctAnswers;
                finish.totalQuestions = session.totalQuestions;
                response = withAuthenticatedRetries(token -> remoteDataSource.finishQuiz(token, session.remoteSessionId, finish));
            } else if ("REMOTE_CANCEL_PENDING".equals(session.state)) {
                response = withAuthenticatedRetries(token -> remoteDataSource.cancelQuiz(token, session.remoteSessionId));
            } else {
                QuizApiModels.OfflineQuizSyncRequest sync = new QuizApiModels.OfflineQuizSyncRequest();
                sync.clientSessionId = session.id;
                sync.topicId = Integer.parseInt(session.topicId);
                sync.mode = session.mode;
                sync.difficulty = session.difficulty;
                sync.stake = session.stake;
                sync.totalQuestions = session.totalQuestions;
                sync.correctAnswers = session.correctAnswers;
                sync.cancelled = "CANCEL_PENDING".equals(session.state);
                response = withAuthenticatedRetries(token -> remoteDataSource.syncOfflineQuiz(token, sync));
            }
            if (response != null) {
                android.util.Log.i("QuizRepository", "offline-sync: session=" + session.id
                        + ", state=" + session.state
                        + ", localBalanceBeforeResponse=" + QuizApplication.getCurrencyBalance(appContext)
                        + ", serverBalance=" + response.balance
                        + ", stake=" + response.stake
                        + ", reward=" + response.rewardAmount);
                QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
                quizDao.updateUserCurrencyBalance(getUserId(), response.balance);
            }
            quizDao.deleteOfflineQuizSession(session.id);
        }
        android.util.Log.i("QuizRepository", "offline-sync: completed, localBalanceAfter="
                + QuizApplication.getCurrencyBalance(appContext));
    }

    private void syncPendingAssetOperations() throws Exception {
        if (!NetworkState.isAvailable()) {
            throw new IOException("Network is unavailable");
        }
        List<PendingAssetOperationEntity> pending = quizDao.getPendingAssetOperations();
        android.util.Log.i("QuizRepository", "asset-sync: pending="
                + (pending == null ? 0 : pending.size())
                + ", localBalanceBefore=" + QuizApplication.getCurrencyBalance(appContext));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (PendingAssetOperationEntity operation : pending) {
            if (!NetworkState.isAvailable()) {
                throw new IOException("Network is unavailable");
            }
            try {
                QuizApiModels.ActionResponse response;
                if ("PURCHASE".equals(operation.operationType)) {
                    response = withAuthenticatedRetries(token -> remoteDataSource.purchaseAsset(
                            token,
                            Integer.parseInt(operation.assetId),
                            operation.operationId
                    ));
                } else {
                    response = withAuthenticatedRetries(token -> remoteDataSource.selectAsset(
                            token,
                            Integer.parseInt(operation.assetId)
                    ));
                }
                if (response != null && "PURCHASE".equals(operation.operationType)) {
                    android.util.Log.i("QuizRepository", "asset-sync: operation=" + operation.operationId
                            + ", asset=" + operation.assetId
                            + ", localBalanceBeforeResponse=" + QuizApplication.getCurrencyBalance(appContext)
                            + ", serverBalance=" + response.balance);
                    quizDao.updateUserCurrencyBalance(operation.userId, response.balance);
                    QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
                }
                quizDao.deletePendingAssetOperation(operation.operationId);
            } catch (Exception error) {
                if ("PURCHASE".equals(operation.operationType) && isConflict(error)) {
                    rollbackPendingAssetPurchase(operation);
                    continue;
                }
                if ("SELECT".equals(operation.operationType) && isConflict(error)) {
                    rollbackPendingAssetSelection(operation);
                    continue;
                }
                throw error;
            }
        }
        android.util.Log.i("QuizRepository", "asset-sync: completed, localBalanceAfter="
                + QuizApplication.getCurrencyBalance(appContext));
    }

    private void rollbackPendingAssetPurchase(PendingAssetOperationEntity operation) {
        quizDatabase.runInTransaction(() -> {
            quizDao.deleteUserAsset(operation.userId, operation.assetId);
            quizDao.updateUserCurrencyBalance(operation.userId, operation.balanceBefore);
            quizDao.clearSelectedForUserAndType(operation.userId, operation.assetType);
            if (operation.previousSelectedAssetId != null && !operation.previousSelectedAssetId.isEmpty()) {
                quizDao.selectAsset(operation.userId, operation.previousSelectedAssetId);
            }
            quizDao.deletePendingAssetOperation(operation.operationId);
        });
        QuizApplication.setDisplayedCurrencyBalance(appContext, operation.balanceBefore);
    }

    private void rollbackPendingAssetSelection(PendingAssetOperationEntity operation) {
        quizDatabase.runInTransaction(() -> {
            quizDao.clearSelectedForUserAndType(operation.userId, operation.assetType);
            if (operation.previousSelectedAssetId != null && !operation.previousSelectedAssetId.isEmpty()) {
                quizDao.selectAsset(operation.userId, operation.previousSelectedAssetId);
            }
            quizDao.deletePendingAssetOperation(operation.operationId);
        });
    }

    private QuizApiModels.ActionResponse withNetworkRetries(RemoteCall call) throws Exception {
        Exception lastError = null;
        long deadline = System.nanoTime() + NETWORK_OPERATION_BUDGET_MS * 1_000_000L;
        for (int attempt = 0; attempt < MAX_NETWORK_ATTEMPTS; attempt++) {
            if (attempt > 0 && !NetworkState.isAvailable()) {
                break;
            }
            try {
                return call.execute();
            } catch (Exception error) {
                lastError = error;
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (attempt == MAX_NETWORK_ATTEMPTS - 1 || remainingMs <= 0 || !NetworkState.isAvailable()) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(200L, Math.max(1L, remainingMs)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Network is unavailable");
    }

    private interface RemoteCall {
        QuizApiModels.ActionResponse execute() throws Exception;
    }

    private interface AuthenticatedRemoteCall {
        QuizApiModels.ActionResponse execute(String accessToken) throws Exception;
    }

    private QuizApiModels.ActionResponse withAuthenticatedRetries(AuthenticatedRemoteCall call) throws Exception {
        try {
            return withNetworkRetries(() -> call.execute(authenticateIfNeeded()));
        } catch (Exception error) {
            if (!isUnauthorized(error)) {
                throw error;
            }
            clearStoredAuth();
            return withNetworkRetries(() -> call.execute(authenticateIfNeeded()));
        }
    }

    private boolean isUnauthorized(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("HTTP 401") || message.contains("401 Unauthorized"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("HTTP 409") || message.contains("409 Conflict"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isNetworkFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.US);
                if (normalized.contains("timeout")
                        || normalized.contains("failed to connect")
                        || normalized.contains("unable to resolve host")
                        || normalized.contains("network is unavailable")
                        || normalized.contains("connection reset")
                        || normalized.contains("unexpected end of stream")
                        || normalized.contains("broken pipe")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** Removes only unsupported/stale locales; downloaded supported languages are retained. */
    public void pruneCachedLanguages(String language) {
        if (quizDatabase == null) {
            return;
        }
        // Do not reduce this list to the currently selected language. A user
        // may have downloaded Russian, switched to Ukrainian, and then go
        // offline; both cached translations must remain usable.
        List<String> allowed = Arrays.asList("en", "ru", "uk");
        quizDatabase.runInTransaction(() -> {
            quizDao.deleteTopicTextsExceptLanguages(allowed);
            quizDao.deleteQuestionTextsExceptLanguages(allowed);
            quizDao.deleteOptionTextsExceptLanguages(allowed);
        });
    }

    public void applyServerBalanceLocally(int balance) {
        if (quizDatabase != null && appContext != null) {
            quizDao.updateUserCurrencyBalance(getUserId(), balance);
        }
    }

    public QuizApiModels.ActionResponse purchaseAsset(int assetId) throws Exception {
        String operationId = "asset_purchase_" + UUID.randomUUID();
        return withAuthenticatedRetries(token -> remoteDataSource.purchaseAsset(token, assetId, operationId));
    }

    /**
     * Buys an asset immediately from the local cache when offline. The local
     * balance and ownership are changed in one transaction; the same stable
     * operation id is later retried against the server.
     */
    public AssetPurchaseResult purchaseAssetWithOfflineSupport(int assetId) throws Exception {
        if (quizDatabase == null || appContext == null) {
            throw new IllegalStateException("Local database is unavailable");
        }

        if (quizDao.countPendingAssetOperations() > 0 && NetworkState.isAvailable()) {
            syncPendingAssetOperationsBlocking();
        }

        String userId = getUserId();
        AssetEntity asset = quizDao.getAssetById(String.valueOf(assetId));
        if (asset == null || !asset.isActive) {
            throw new IllegalStateException("Asset is unavailable offline");
        }
        List<UserAssetEntity> ownedAssets = quizDao.getUserAssets(userId);
        if (ownedAssets != null) {
            for (UserAssetEntity owned : ownedAssets) {
                if (String.valueOf(assetId).equals(owned.assetId)) {
                    UserEntity localUser = quizDao.getUserById(userId);
                    int currentBalance = localUser == null
                            ? QuizApplication.getCurrencyBalance(appContext)
                            : localUser.currencyBalance;
                    return new AssetPurchaseResult(currentBalance, false);
                }
            }
        }

        String operationId = "asset_purchase_" + UUID.randomUUID();
        if (NetworkState.isAvailable()) {
            try {
                QuizApiModels.ActionResponse response = withAuthenticatedRetries(
                        token -> remoteDataSource.purchaseAsset(token, assetId, operationId)
                );
                return new AssetPurchaseResult(response.balance, false);
            } catch (Exception error) {
                if (!isNetworkFailure(error)) {
                    throw error;
                }
                android.util.Log.w("QuizRepository", "Asset purchase moved to offline queue", error);
            }
        }
        return queueOfflineAssetPurchase(asset, userId, operationId);
    }

    private AssetPurchaseResult queueOfflineAssetPurchase(
            AssetEntity asset,
            String userId,
            String operationId
    ) {
        UserEntity localUser = quizDao.getUserById(userId);
        if (localUser == null) {
            throw new IllegalStateException("Offline profile is not ready yet");
        }
        if (localUser.currencyBalance < asset.price) {
            throw new IllegalStateException("Not enough coins");
        }

        int balanceAfter = localUser.currencyBalance - asset.price;
        PendingAssetOperationEntity pending = new PendingAssetOperationEntity();
        pending.operationId = operationId;
        pending.userId = userId;
        pending.operationType = "PURCHASE";
        pending.assetId = asset.id;
        pending.assetType = asset.assetType;
        pending.price = asset.price;
        pending.previousSelectedAssetId = quizDao.getSelectedAssetIdForType(userId, asset.assetType);
        pending.balanceBefore = localUser.currencyBalance;
        pending.createdAt = System.currentTimeMillis();

        quizDatabase.runInTransaction(() -> {
            quizDao.updateUserCurrencyBalance(userId, balanceAfter);
            quizDao.clearSelectedForUserAndType(userId, asset.assetType);
            UserAssetEntity userAsset = new UserAssetEntity();
            userAsset.userId = userId;
            userAsset.assetId = asset.id;
            userAsset.selected = true;
            userAsset.purchasedAt = System.currentTimeMillis();
            quizDao.upsertUserAsset(userAsset);
            quizDao.upsertPendingAssetOperation(pending);
        });
        QuizApplication.setDisplayedCurrencyBalance(appContext, balanceAfter);
        return new AssetPurchaseResult(balanceAfter, true);
    }

    public void applyAssetPurchaseLocally(int assetId, int balance) {
        if (quizDatabase == null || appContext == null) {
            return;
        }
        String userId = getUserId();
        AssetEntity asset = quizDao.getAssetById(String.valueOf(assetId));
        if (asset == null) {
            return;
        }
        quizDatabase.runInTransaction(() -> {
            quizDao.updateUserCurrencyBalance(userId, balance);
            quizDao.clearSelectedForUserAndType(userId, asset.assetType);
            UserAssetEntity userAsset = new UserAssetEntity();
            userAsset.userId = userId;
            userAsset.assetId = String.valueOf(assetId);
            userAsset.selected = true;
            userAsset.purchasedAt = System.currentTimeMillis();
            quizDao.upsertUserAsset(userAsset);
        });
    }

    public QuizApiModels.ActionResponse selectAsset(int assetId) throws Exception {
        return withAuthenticatedRetries(token -> remoteDataSource.selectAsset(token, assetId));
    }

    public boolean selectAssetWithOfflineSupport(
            int assetId,
            String assetType,
            String previousSelectedAssetId
    ) throws Exception {
        if (quizDatabase == null || appContext == null) {
            throw new IllegalStateException("Local database is unavailable");
        }
        String operationId = "asset_select_" + UUID.randomUUID();
        if (NetworkState.isAvailable()) {
            try {
                selectAsset(assetId);
                return false;
            } catch (Exception error) {
                if (!isNetworkFailure(error)) {
                    throw error;
                }
                android.util.Log.w("QuizRepository", "Asset selection moved to offline queue", error);
            }
        }

        PendingAssetOperationEntity pending = new PendingAssetOperationEntity();
        pending.operationId = operationId;
        pending.userId = getUserId();
        pending.operationType = "SELECT";
        pending.assetId = String.valueOf(assetId);
        pending.assetType = assetType == null ? "" : assetType;
        pending.price = 0;
        pending.previousSelectedAssetId = previousSelectedAssetId;
        pending.balanceBefore = QuizApplication.getCurrencyBalance(appContext);
        pending.createdAt = System.currentTimeMillis();
        quizDao.upsertPendingAssetOperation(pending);
        return true;
    }

    public QuizApiModels.ActionResponse topUpTestCurrency() throws Exception {
        if (!TOP_UP_IN_PROGRESS.compareAndSet(false, true)) {
            throw new IllegalStateException("Currency top-up is already in progress");
        }
        try {
            prepareBalanceForTopUp();
            String operationId = "topup_" + UUID.randomUUID();
            QuizApiModels.ActionResponse response = withAuthenticatedRetries(
                    token -> remoteDataSource.topUpTestCurrency(token, operationId));
            if (appContext != null) {
                quizDao.updateUserCurrencyBalance(getUserId(), response.balance);
                QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
            }
            return response;
        } finally {
            TOP_UP_IN_PROGRESS.set(false);
        }
    }

    public QuizApiModels.ActionResponse topUpAdCurrency() throws Exception {
        return topUpAdCurrency("topup_" + UUID.randomUUID());
    }

    public QuizApiModels.ActionResponse topUpAdCurrency(String operationId) throws Exception {
        if (!TOP_UP_IN_PROGRESS.compareAndSet(false, true)) {
            throw new IllegalStateException("Currency top-up is already in progress");
        }
        try {
            prepareBalanceForTopUp();
            QuizApiModels.ActionResponse response = withAuthenticatedRetries(
                    token -> remoteDataSource.topUpAdCurrency(token, operationId));
            if (appContext != null) {
                quizDao.updateUserCurrencyBalance(getUserId(), response.balance);
                QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
            }
            return response;
        } finally {
            TOP_UP_IN_PROGRESS.set(false);
        }
    }

    public QuizApiModels.ActionResponse verifyGooglePlayPurchase(
            String productId,
            String purchaseToken
    ) throws Exception {
        QuizApiModels.ActionResponse response = withAuthenticatedRetries(
                token -> remoteDataSource.verifyGooglePlayPurchase(token, productId, purchaseToken)
        );
        if (appContext != null && response != null) {
            quizDao.updateUserCurrencyBalance(getUserId(), response.balance);
            QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
        }
        return response;
    }

    private void prepareBalanceForTopUp() throws Exception {
        syncPendingAssetOperationsBlocking();
        List<OfflineQuizSessionEntity> pending = quizDao.getPendingOfflineQuizSessions();
        if (pending != null && !pending.isEmpty()) {
            // The sync response already contains the canonical balance.
            syncOfflineSessionsBlocking();
        } else {
            // If there is no outbox, reconcile the potentially stale local value.
            reconcileServerBalanceBlocking();
        }
    }

    /** Reads the server balance and updates the local display before a paid/test action. */
    public int reconcileServerBalanceBlocking() throws Exception {
        QuizApiModels.ActionResponse response = withAuthenticatedRetries(
                remoteDataSource::fetchBalance
        );
        if (appContext != null && response != null) {
            quizDao.updateUserCurrencyBalance(getUserId(), response.balance);
            QuizApplication.setDisplayedCurrencyBalance(appContext, response.balance);
            return response.balance;
        }
        return QuizApplication.getCurrencyBalance(appContext);
    }

    private String authenticateIfNeeded() throws Exception {
        String token = getAccessToken();
        if (token != null && !token.isEmpty()) {
            return token;
        }
        if (appContext == null) {
            throw new IllegalStateException("Repository has no application context");
        }
        SharedPreferences preferences = prefs();
        String deviceId = preferences.getString(PREF_DEVICE_ID, null);
        if (deviceId == null || deviceId.length() < 8) {
            deviceId = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.length() < 8) {
                deviceId = UUID.randomUUID().toString();
            }
            preferences.edit().putString(PREF_DEVICE_ID, deviceId).apply();
        }
        QuizApiModels.AuthResponse auth = remoteDataSource.authenticate(deviceId);
        preferences.edit().putString(PREF_AUTH_TOKEN, auth.accessToken)
                .putString(PREF_USER_ID, auth.userId).apply();
        return auth.accessToken;
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(appContext);
    }

    private void clearStoredAuth() {
        if (appContext != null) {
            prefs().edit().remove(PREF_AUTH_TOKEN).remove(PREF_USER_ID).apply();
        }
    }

    private void prepareLanguageCache(List<String> languages) {
        // The bootstrap response contains only the requested language and
        // English. Replace those two snapshots, but preserve every other
        // supported language already downloaded on this device.
        for (String language : languages) {
            quizDao.clearTopicTextsForLanguage(language);
            quizDao.clearQuestionTextsForLanguage(language);
            quizDao.clearOptionTextsForLanguage(language);
        }
    }

    private void logLanguageCacheState(List<String> languages, String source) {
        if (languages == null || languages.isEmpty()) {
            return;
        }
        for (String language : languages) {
            android.util.Log.i("QuizRepository", "language-cache: source=" + source
                    + ", language=" + language
                    + ", topics=" + quizDao.countTopicTextsForLanguage(language)
                    + ", questions=" + quizDao.countQuestionTextsForLanguage(language)
                    + ", options=" + quizDao.countOptionTextsForLanguage(language));
        }
    }

    private void clearServerData() {
        quizDao.clearOptionTexts();
        quizDao.clearOptions();
        quizDao.clearQuestionTextsForSync();
        quizDao.clearQuestions();
        quizDao.clearTopicTexts();
        quizDao.clearQuizSessions();
        quizDao.clearCurrencyTransactions();
        quizDao.clearUserAssets();
        quizDao.clearAssets();
        quizDao.clearUsers();
        quizDao.clearTopics();
    }

    private void saveBootstrapTopics(BootstrapDto bootstrap) {
        if (bootstrap == null) {
            return;
        }

        quizDao.upsertTopics(mapTopics(bootstrap.topics));
        quizDao.upsertTopicTexts(mapTopicTexts(bootstrap.topicTexts));
    }

    private void saveBootstrapAssets(BootstrapDto bootstrap) {
        if (bootstrap == null) {
            return;
        }

        // user_assets has foreign keys to both users and assets, so both parent
        // records must exist before inserting the ownership rows.
        UserEntity serverUser = mapFirstUser(bootstrap.users);
        int localBalanceBefore = appContext == null
                ? -1
                : QuizApplication.getCurrencyBalance(appContext);
        quizDao.upsertUser(serverUser);
        if (appContext != null && bootstrap.users != null && !bootstrap.users.isEmpty()) {
            // The server is authoritative after outboxes have been flushed.
            // Keep the displayed preference in sync with Room as well, so the
            // next screen cannot show an old local balance.
            QuizApplication.setDisplayedCurrencyBalance(appContext, serverUser.currencyBalance);
            android.util.Log.i("QuizRepository", "bootstrap: serverBalance="
                    + serverUser.currencyBalance + ", localBalanceBefore=" + localBalanceBefore);
        }
        quizDao.upsertAssets(mapAssets(bootstrap.assets));
        quizDao.upsertUserAssets(mapUserAssets(bootstrap.userAssets));
        quizDao.upsertCurrencyTransactions(mapTransactions(bootstrap.currencyTransactions));
        quizDao.upsertSessions(mapSessions(bootstrap.quizSessions));
    }

    private void saveBootstrapQuestions(BootstrapDto bootstrap) {
        if (bootstrap == null) {
            return;
        }

        quizDao.upsertQuestions(mapQuestions(bootstrap.questions));
        quizDao.upsertQuestionTexts(mapQuestionTexts(bootstrap.questionTexts));
        quizDao.upsertOptions(mapOptions(bootstrap.options));
        quizDao.upsertOptionTexts(mapOptionTexts(bootstrap.optionTexts));
    }

    private List<TopicEntity> mapTopics(List<BootstrapDto.TopicDto> source) {
        List<TopicEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.TopicDto dto : source) {
            TopicEntity entity = new TopicEntity();
            entity.id = String.valueOf(dto.id);
            entity.code = safeString(dto.code, entity.id);
            entity.iconUrl = dto.iconUrl;
            entity.createdAt = dto.createdAt;
            entity.updatedAt = dto.updatedAt;
            entity.playsCount = dto.playsCount;
            entity.likesCount = dto.likesCount;
            entity.viewsCount = dto.viewsCount;
            entity.authorUserId = safeString(dto.authorUserId, "");
            entity.isPublic = dto.isPublic;
            entity.isActive = dto.isActive;
            result.add(entity);
        }
        return result;
    }

    private List<TopicTextEntity> mapTopicTexts(List<BootstrapDto.TopicTextDto> source) {
        List<TopicTextEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.TopicTextDto dto : source) {
            TopicTextEntity entity = new TopicTextEntity();
            entity.topicId = String.valueOf(dto.topicId);
            entity.languageCode = safeString(dto.languageCode, "en");
            entity.title = safeString(dto.title, entity.topicId);
            entity.description = dto.description;
            entity.abbr = safeString(dto.abbr, entity.title);
            result.add(entity);
        }
        return result;
    }

    private List<QuestionEntity> mapQuestions(List<BootstrapDto.QuestionDto> source) {
        List<QuestionEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.QuestionDto dto : source) {
            QuestionEntity entity = new QuestionEntity();
            entity.id = String.valueOf(dto.id);
            entity.topicId = String.valueOf(dto.topicId);
            entity.difficulty = DifficultyLevel.fromLegacyMode(dto.difficulty);
            entity.isActive = dto.isActive;
            result.add(entity);
        }
        return result;
    }

    private List<QuestionTextEntity> mapQuestionTexts(List<BootstrapDto.QuestionTextDto> source) {
        List<QuestionTextEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.QuestionTextDto dto : source) {
            QuestionTextEntity entity = new QuestionTextEntity();
            entity.questionId = String.valueOf(dto.questionId);
            entity.languageCode = safeString(dto.languageCode, "en");
            entity.questionText = safeString(dto.questionText, "");
            entity.explanation = dto.explanation;
            result.add(entity);
        }
        return result;
    }

    private List<OptionEntity> mapOptions(List<BootstrapDto.OptionDto> source) {
        List<OptionEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.OptionDto dto : source) {
            OptionEntity entity = new OptionEntity();
            entity.id = String.valueOf(dto.id);
            entity.questionId = String.valueOf(dto.questionId);
            entity.isCorrect = dto.isCorrect;
            result.add(entity);
        }
        return result;
    }

    private List<OptionTextEntity> mapOptionTexts(List<BootstrapDto.OptionTextDto> source) {
        List<OptionTextEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.OptionTextDto dto : source) {
            OptionTextEntity entity = new OptionTextEntity();
            entity.optionId = String.valueOf(dto.optionId);
            entity.languageCode = safeString(dto.languageCode, "en");
            entity.optionText = safeString(dto.optionText, "");
            result.add(entity);
        }
        return result;
    }

    private List<AssetEntity> mapAssets(List<BootstrapDto.AssetDto> source) {
        List<AssetEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.AssetDto dto : source) {
            AssetEntity entity = new AssetEntity();
            entity.id = String.valueOf(dto.id);
            entity.assetType = safeString(dto.assetType, "FRAME");
            entity.assetCode = safeString(dto.assetCode, String.valueOf(dto.id));
            entity.price = dto.price;
            entity.isActive = dto.isActive;
            result.add(entity);
        }
        return result;
    }

    private List<UserAssetEntity> mapUserAssets(List<BootstrapDto.UserAssetDto> source) {
        List<UserAssetEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.UserAssetDto dto : source) {
            UserAssetEntity entity = new UserAssetEntity();
            entity.userId = safeString(dto.userId, getUserId());
            entity.assetId = String.valueOf(dto.assetId);
            entity.selected = dto.selected;
            entity.purchasedAt = dto.purchasedAt != null ? dto.purchasedAt : System.currentTimeMillis();
            result.add(entity);
        }
        return result;
    }

    private UserEntity mapFirstUser(List<BootstrapDto.UserDto> source) {
        if (source != null && !source.isEmpty()) {
            BootstrapDto.UserDto dto = source.get(0);
            UserEntity entity = new UserEntity();
            entity.id = safeString(dto.id, "user_test");
            entity.googleUid = dto.googleUid;
            entity.email = dto.email;
            entity.displayName = dto.displayName;
            entity.photoUrl = dto.photoUrl;
            entity.currencyBalance = dto.currencyBalance;
            entity.lastLoginAt = dto.lastLoginAt != null ? dto.lastLoginAt : System.currentTimeMillis();
            return entity;
        }
        UserEntity fallback = new UserEntity();
            fallback.id = getUserId();
        fallback.googleUid = "google_test_001";
        fallback.email = "user@example.com";
        fallback.displayName = "Quiz Player";
        fallback.photoUrl = "";
        fallback.currencyBalance = 0;
        fallback.lastLoginAt = System.currentTimeMillis();
        return fallback;
    }

    private List<CurrencyTransactionEntity> mapTransactions(List<BootstrapDto.CurrencyTransactionDto> source) {
        List<CurrencyTransactionEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.CurrencyTransactionDto dto : source) {
            CurrencyTransactionEntity entity = new CurrencyTransactionEntity();
            entity.id = String.valueOf(dto.id);
            entity.userId = safeString(dto.userId, getUserId());
            entity.amount = dto.amount;
            entity.reason = safeString(dto.reason, "sync");
            result.add(entity);
        }
        return result;
    }

    private QuizSessionEntity mapFirstSession(List<BootstrapDto.QuizSessionDto> source) {
        if (source != null && !source.isEmpty()) {
            BootstrapDto.QuizSessionDto dto = source.get(0);
            QuizSessionEntity entity = new QuizSessionEntity();
            entity.id = String.valueOf(dto.id);
            entity.userId = safeString(dto.userId, "user_test");
            entity.topicId = String.valueOf(dto.topicId);
            entity.mode = safeString(dto.mode, "solo");
            entity.difficulty = DifficultyLevel.fromLegacyMode(dto.difficulty);
            entity.totalQuestions = dto.totalQuestions;
            entity.correctAnswers = dto.correctAnswers;
            entity.startedAt = dto.startedAt;
            entity.finishedAt = dto.finishedAt != null ? dto.finishedAt : dto.startedAt;
            return entity;
        }
        QuizSessionEntity fallback = new QuizSessionEntity();
        fallback.id = "session_seed_001";
            fallback.userId = getUserId();
        fallback.topicId = "1";
        fallback.mode = "solo";
        fallback.difficulty = DifficultyLevel.EASY;
        fallback.totalQuestions = 10;
        fallback.correctAnswers = 0;
        fallback.startedAt = System.currentTimeMillis();
        fallback.finishedAt = fallback.startedAt;
        return fallback;
    }

    private List<QuizSessionEntity> mapSessions(List<BootstrapDto.QuizSessionDto> source) {
        List<QuizSessionEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (BootstrapDto.QuizSessionDto dto : source) {
            QuizSessionEntity entity = new QuizSessionEntity();
            entity.id = String.valueOf(dto.id);
            entity.userId = safeString(dto.userId, getUserId());
            entity.topicId = String.valueOf(dto.topicId);
            entity.mode = safeString(dto.mode, "solo");
            entity.difficulty = DifficultyLevel.fromLegacyMode(dto.difficulty);
            entity.totalQuestions = dto.totalQuestions;
            entity.correctAnswers = dto.correctAnswers;
            entity.stake = dto.stake;
            entity.rewardAmount = dto.rewardAmount;
            entity.startedAt = dto.startedAt;
            entity.finishedAt = dto.finishedAt != null ? dto.finishedAt : 0L;
            result.add(entity);
        }
        return result;
    }

    private String safeString(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
