package com.maxim.itquiz.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local currency action waiting to be accepted by the server. */
@Entity(
        tableName = "pending_currency_operations",
        indices = {
                @Index(value = {"user_id"}),
                @Index(value = {"created_at"})
        }
)
public class PendingCurrencyOperationEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "operation_id")
    public String operationId;

    @NonNull
    @ColumnInfo(name = "user_id")
    public String userId;

    @NonNull
    @ColumnInfo(name = "source")
    public String source;

    @ColumnInfo(name = "amount")
    public int amount;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
