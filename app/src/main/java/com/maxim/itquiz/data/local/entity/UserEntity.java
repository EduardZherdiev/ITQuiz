package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "users")
public class UserEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "google_uid")
    public String googleUid;

    @ColumnInfo(name = "email")
    public String email;

    @ColumnInfo(name = "display_name")
    public String displayName;

    @ColumnInfo(name = "photo_url")
    public String photoUrl;

    @ColumnInfo(name = "currency_balance")
    public int currencyBalance;

    @ColumnInfo(name = "last_login_at")
    public long lastLoginAt;
}
