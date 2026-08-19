package com.maxim.itquiz.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.maxim.itquiz.R;

public class AutoFitGridRecyclerView extends RecyclerView {

    private final GridLayoutManager gridLayoutManager;
    private int columnWidth;

    public AutoFitGridRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public AutoFitGridRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitGridRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gridLayoutManager = new GridLayoutManager(context, getResources().getInteger(R.integer.topics_min_span_count));
        setLayoutManager(gridLayoutManager);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.columnWidth});
            columnWidth = a.getDimensionPixelSize(0, 0);
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (columnWidth <= 0) {
            return;
        }

        int availableWidth = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
        int spanCount = Math.max(getResources().getInteger(R.integer.topics_min_span_count), availableWidth / columnWidth);
        gridLayoutManager.setSpanCount(spanCount);
    }
}
