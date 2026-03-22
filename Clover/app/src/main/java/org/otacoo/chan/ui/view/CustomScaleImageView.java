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

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

public class CustomScaleImageView extends SubsamplingScaleImageView {
    private Callback callback;

    public CustomScaleImageView(Context context) {
        this(context, null);
    }

    @SuppressWarnings("this-escape")
    public CustomScaleImageView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Image zooming
        setMinimumDpi(60);
        setDoubleTapZoomDpi(120);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    protected void onImageLoaded() {
        super.onImageLoaded();
        if (callback != null) {
            callback.onReady();
        }
    }

    @Override
    protected void onReady() {
        super.onReady();
        if (callback != null) {
            callback.onReady();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // If we have multiple pointers, we are likely zooming/pinching.
        if (event.getPointerCount() > 1) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        boolean result = super.onTouchEvent(event);

        // If we are zoomed in, don't let parent ViewPager intercept our swipes
        if (getScale() > getMinScale()) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        return result;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public interface Callback {
        void onReady();
        void onError(boolean wasInitial);
    }
}
