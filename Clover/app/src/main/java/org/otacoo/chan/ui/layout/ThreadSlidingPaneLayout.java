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
package org.otacoo.chan.ui.layout;

import static org.otacoo.chan.utils.AndroidUtils.dp;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;

import androidx.slidingpanelayout.widget.SlidingPaneLayout;

import org.otacoo.chan.R;
import org.otacoo.chan.ui.controller.ThreadSlideController;
import org.otacoo.chan.utils.AndroidUtils;

public class ThreadSlidingPaneLayout extends SlidingPaneLayout {
    public ViewGroup leftPane;
    public ViewGroup rightPane;

    private ThreadSlideController threadSlideController;

    public ThreadSlidingPaneLayout(Context context) {
        this(context, null);
    }

    public ThreadSlidingPaneLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThreadSlidingPaneLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        leftPane = findViewById(R.id.left_pane);
        rightPane = findViewById(R.id.right_pane);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Forces a relayout after it has already been layed out, because SlidingPaneLayout sucks and otherwise
        // gives the children too much room until they request a relayout.
        AndroidUtils.waitForLayout(this, new AndroidUtils.OnMeasuredCallback() {
            @Override
            public boolean onMeasured(View view) {
                requestLayout();
                return false;
            }
        });
    }

    public void setThreadSlideController(ThreadSlideController threadSlideController) {
        this.threadSlideController = threadSlideController;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        super.onRestoreInstanceState(state);
        if (threadSlideController != null) {
            threadSlideController.onSlidingPaneLayoutStateRestored();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ViewDragHelper can receive horizontal drag events on Android 10+.
            int edgeWidth = dp(20);
            setSystemGestureExclusionRects(
                    Collections.singletonList(new Rect(0, 0, edgeWidth, getHeight())));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        ViewGroup.LayoutParams leftParams = leftPane.getLayoutParams();
        ViewGroup.LayoutParams rightParams = rightPane.getLayoutParams();

        if (width > 0) {
            if (width < dp(500)) {
                leftParams.width = Math.max(0, width - dp(30));
                rightParams.width = width;
            } else {
                leftParams.width = Math.max(0, width - dp(60));
                rightParams.width = width;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
