package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "option_texts",
        primaryKeys = {"option_id", "language_code"},
        foreignKeys = {
                @ForeignKey(
                        entity = OptionEntity.class,
                        parentColumns = "id",
                        childColumns = "option_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index(value = {"option_id"})}
)
public class OptionTextEntity {

    @NonNull
    @ColumnInfo(name = "option_id")
    public String optionId;

    @NonNull
    @ColumnInfo(name = "language_code")
    public String languageCode;

    @NonNull
    @ColumnInfo(name = "option_text")
    public String optionText;
}
