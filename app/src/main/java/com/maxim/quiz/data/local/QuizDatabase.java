package com.maxim.quiz.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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

@Database(
        entities = {
                TopicEntity.class,
                TopicTextEntity.class,
                QuestionEntity.class,
                QuestionTextEntity.class,
                OptionEntity.class,
                OptionTextEntity.class,
                UserEntity.class,
                QuizSessionEntity.class,
                AssetEntity.class,
                UserAssetEntity.class,
                CurrencyTransactionEntity.class,
                OfflineQuizSessionEntity.class,
                PendingAssetOperationEntity.class
        },
            version = 7,
        exportSchema = false
)
public abstract class QuizDatabase extends RoomDatabase {

    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS offline_quiz_sessions (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "user_id TEXT NOT NULL, " +
                    "topic_id TEXT NOT NULL, " +
                    "mode TEXT NOT NULL, " +
                    "difficulty TEXT NOT NULL, " +
                    "total_questions INTEGER NOT NULL, " +
                    "stake INTEGER NOT NULL, " +
                    "correct_answers INTEGER NOT NULL, " +
                    "reward_amount INTEGER NOT NULL, " +
                    "state TEXT NOT NULL, " +
                    "remote_session_id TEXT, " +
                    "started_at INTEGER NOT NULL, " +
                    "finished_at INTEGER NOT NULL)");
        }
    };

    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS pending_asset_operations (" +
                    "operation_id TEXT NOT NULL PRIMARY KEY, " +
                    "user_id TEXT NOT NULL, " +
                    "operation_type TEXT NOT NULL, " +
                    "asset_id TEXT NOT NULL, " +
                    "asset_type TEXT NOT NULL, " +
                    "price INTEGER NOT NULL, " +
                    "previous_selected_asset_id TEXT, " +
                    "balance_before INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_asset_operations_user_id " +
                    "ON pending_asset_operations (user_id)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_asset_operations_created_at " +
                    "ON pending_asset_operations (created_at)");
        }
    };

    private static volatile QuizDatabase INSTANCE;

    public abstract QuizDao quizDao();

    public static QuizDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (QuizDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    QuizDatabase.class,
                                    "quiz.db"
                            )
                                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                            .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
