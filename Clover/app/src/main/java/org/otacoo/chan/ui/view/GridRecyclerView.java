/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.ui.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView with a GridLayoutManager that manages the span count by dividing the width of the
 * view with the value set by {@link #setSpanWidth(int)}.
 */
public class GridRecyclerView extends RecyclerView {
    private GridLayoutManager gridLayoutManager;
    private int spanWidth;
    private int fixedSpanCount = 0;
    private int realSpanWidth;

    public GridRecyclerView(Context context) {
        super(context);
    }

    public GridRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GridRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setLayoutManager(GridLayoutManager gridLayoutManager) {
        this.gridLayoutManager = gridLayoutManager;
        super.setLayoutManager(gridLayoutManager);
    }

    /**
     * Set the width of each span in pixels.
     *
     * @param spanWidth width of each span in pixels.
     */
    public void setSpanWidth(int spanWidth) {
        this.spanWidth = spanWidth;
    }

    public void setFixedSpanCount(int spanCount) {
        this.fixedSpanCount = spanCount;
        requestLayout();
    }

    public int getRealSpanWidth() {
        return realSpanWidth;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (gridLayoutManager == null) return;
        int spanCount = fixedSpanCount > 0 ? fixedSpanCount
                : (spanWidth > 0 ? Math.max(1, getMeasuredWidth() / spanWidth) : 1);
        if (gridLayoutManager.getSpanCount() != spanCount) {
            gridLayoutManager.setSpanCount(spanCount);
        }
        int newRealSpanWidth = getMeasuredWidth() / Math.max(1, spanCount);
        if (newRealSpanWidth != realSpanWidth) {
            realSpanWidth = newRealSpanWidth;
            if (getAdapter() != null) {
                // Never notify synchronously from measure (RecyclerView may be
                // computing layout); defer past the current traversal.
                removeCallbacks(refreshRunnable);
                post(refreshRunnable);
            }
        }
    }

    private final Runnable refreshRunnable = () -> {
        if (getAdapter() != null) {
            getAdapter().notifyDataSetChanged();
        }
    };
}
