package com.maxim.quiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "assets")
public class AssetEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "asset_type")
    public String assetType;

    @ColumnInfo(name = "asset_code")
    public String assetCode;

    @ColumnInfo(name = "price")
    public int price;

    @ColumnInfo(name = "is_active")
    public boolean isActive;
}
