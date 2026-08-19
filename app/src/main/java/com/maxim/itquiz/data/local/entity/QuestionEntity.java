package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "questions",
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
public class QuestionEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "topic_id")
    public String topicId;

    @ColumnInfo(name = "difficulty")
        public int difficulty;

    @ColumnInfo(name = "is_active")
    public boolean isActive;
}
