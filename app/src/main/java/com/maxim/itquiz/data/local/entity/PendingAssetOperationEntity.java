package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local asset action waiting for a server connection. */
@Entity(
        tableName = "pending_asset_operations",
        indices = {
                @Index(value = {"user_id"}),
                @Index(value = {"created_at"})
        }
)
public class PendingAssetOperationEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "operation_id")
    public String operationId;

    @NonNull
    @ColumnInfo(name = "user_id")
    public String userId;

    @NonNull
    @ColumnInfo(name = "operation_type")
    public String operationType;

    @NonNull
    @ColumnInfo(name = "asset_id")
    public String assetId;

    @NonNull
    @ColumnInfo(name = "asset_type")
    public String assetType;

    @ColumnInfo(name = "price")
    public int price;

    @ColumnInfo(name = "previous_selected_asset_id")
    public String previousSelectedAssetId;

    @ColumnInfo(name = "balance_before")
    public int balanceBefore;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
