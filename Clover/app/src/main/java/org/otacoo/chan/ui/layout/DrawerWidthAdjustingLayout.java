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
import android.util.AttributeSet;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;

import org.otacoo.chan.R;

public class DrawerWidthAdjustingLayout extends DrawerLayout {
    private View drawerPanel;
    private final java.util.List<android.graphics.Rect> gestureExclusionRects = new java.util.ArrayList<>(2);
    private int lastExclusionWidth = -1;
    private int lastExclusionHeight = -1;
    public DrawerWidthAdjustingLayout(Context context) {
        super(context);
    }

    public DrawerWidthAdjustingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DrawerWidthAdjustingLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        drawerPanel = findViewById(R.id.drawer_panel);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            int width = getWidth();
            int height = getHeight();
            if (width != lastExclusionWidth || height != lastExclusionHeight) {
                lastExclusionWidth = width;
                lastExclusionHeight = height;
                int edgeWidth = org.otacoo.chan.utils.AndroidUtils.dp(20);
                gestureExclusionRects.clear();
                gestureExclusionRects.add(new android.graphics.Rect(0, 0, edgeWidth, height)); // Left edge
                gestureExclusionRects.add(new android.graphics.Rect(width - edgeWidth, 0, width, height)); // Right edge
                setSystemGestureExclusionRects(gestureExclusionRects);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
//        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
//        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
//        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        View drawer = drawerPanel != null ? drawerPanel : findViewById(R.id.drawer_panel);

        int width = Math.min(widthSize - dp(56), dp(56) * 6);
        if (drawer.getLayoutParams().width != width) {
            drawer.getLayoutParams().width = width;
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
