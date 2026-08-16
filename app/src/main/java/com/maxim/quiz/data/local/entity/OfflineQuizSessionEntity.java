package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A quiz started without a server connection. The row is also the outbox
 * record: it remains until the server accepts the same session result.
 */
@Entity(tableName = "offline_quiz_sessions")
public class OfflineQuizSessionEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "user_id")
    public String userId;

    @NonNull
    @ColumnInfo(name = "topic_id")
    public String topicId;

    @NonNull
    @ColumnInfo(name = "mode")
    public String mode;

    @NonNull
    @ColumnInfo(name = "difficulty")
    public String difficulty;

    @ColumnInfo(name = "total_questions")
    public int totalQuestions;

    @ColumnInfo(name = "stake")
    public int stake;

    @ColumnInfo(name = "correct_answers")
    public int correctAnswers;

    @ColumnInfo(name = "reward_amount")
    public int rewardAmount;

    @NonNull
    @ColumnInfo(name = "state")
    public String state;

    @ColumnInfo(name = "remote_session_id")
    public String remoteSessionId;

    @ColumnInfo(name = "started_at")
    public long startedAt;

    @ColumnInfo(name = "finished_at")
    public long finishedAt;
}
