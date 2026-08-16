package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "question_texts",
        primaryKeys = {"question_id", "language_code"},
        foreignKeys = {
                @ForeignKey(
                        entity = QuestionEntity.class,
                        parentColumns = "id",
                        childColumns = "question_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index(value = {"question_id"})}
)
public class QuestionTextEntity {

    @NonNull
    @ColumnInfo(name = "question_id")
    public String questionId;

    @NonNull
    @ColumnInfo(name = "language_code")
    public String languageCode;

    @NonNull
    @ColumnInfo(name = "question_text")
    public String questionText;

    @ColumnInfo(name = "explanation")
    public String explanation;
}
