package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "quiz_sessions",
        foreignKeys = {
                @ForeignKey(
                        entity = UserEntity.class,
                        parentColumns = "id",
                        childColumns = "user_id",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = TopicEntity.class,
                        parentColumns = "id",
                        childColumns = "topic_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index(value = {"user_id"}), @Index(value = {"topic_id"})}
)
public class QuizSessionEntity {

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

    @ColumnInfo(name = "difficulty")
        public int difficulty;

    @ColumnInfo(name = "total_questions")
    public int totalQuestions;

    @ColumnInfo(name = "correct_answers")
    public int correctAnswers;

    @ColumnInfo(name = "stake")
    public int stake;

    @ColumnInfo(name = "reward_amount")
    public int rewardAmount;

    @ColumnInfo(name = "started_at")
    public long startedAt;

    @ColumnInfo(name = "finished_at")
    public long finishedAt;
}
