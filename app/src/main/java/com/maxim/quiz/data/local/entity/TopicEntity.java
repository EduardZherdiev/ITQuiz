package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "topics")
public class TopicEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "code")
    public String code;

    @ColumnInfo(name = "icon_url")
    public String iconUrl;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "plays_count")
    public int playsCount;

    @ColumnInfo(name = "likes_count")
    public int likesCount;

    @ColumnInfo(name = "views_count")
    public int viewsCount;

    @NonNull
    @ColumnInfo(name = "author_user_id")
    public String authorUserId;

    @ColumnInfo(name = "is_public")
    public boolean isPublic;

    @ColumnInfo(name = "is_active")
    public boolean isActive;
}
