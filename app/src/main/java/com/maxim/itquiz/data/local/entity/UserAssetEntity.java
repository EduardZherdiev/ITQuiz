package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "user_assets",
        primaryKeys = {"user_id", "asset_id"},
        foreignKeys = {
                @ForeignKey(
                        entity = UserEntity.class,
                        parentColumns = "id",
                        childColumns = "user_id",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = AssetEntity.class,
                        parentColumns = "id",
                        childColumns = "asset_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index(value = {"user_id"}), @Index(value = {"asset_id"})}
)
public class UserAssetEntity {

    @NonNull
    @ColumnInfo(name = "user_id")
    public String userId;

    @NonNull
    @ColumnInfo(name = "asset_id")
    public String assetId;

    @ColumnInfo(name = "selected")
    public boolean selected;

    @ColumnInfo(name = "purchased_at")
    public long purchasedAt;
}
