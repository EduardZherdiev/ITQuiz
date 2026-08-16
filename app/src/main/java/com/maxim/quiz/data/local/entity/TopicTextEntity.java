package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "topic_texts",
        primaryKeys = {"topic_id", "language_code"},
        foreignKeys = {
                @ForeignKey(
                        entity = TopicEntity.class,
                        parentColumns = "id",
                        childColumns = "topic_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index(value = {"topic_id"})}
)
public class TopicTextEntity {

    @NonNull
    @ColumnInfo(name = "topic_id")
    public String topicId;

    @NonNull
    @ColumnInfo(name = "language_code")
    public String languageCode;

    @NonNull
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "description")
    public String description;

        @ColumnInfo(name = "abbr")
        public String abbr;
}
