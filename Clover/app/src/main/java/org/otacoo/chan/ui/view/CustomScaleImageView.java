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
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

public class CustomScaleImageView extends SubsamplingScaleImageView {
    private Callback callback;

    // Live rotation of image
    private float extraRotation = 0f;
    private float extraScale = 1f;

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

    public void setExtraRotation(float degrees) {
        extraRotation = degrees;
        extraScale = computeFitScale(degrees);
        invalidate();
    }

    public void resetExtraRotation() {
        extraRotation = 0f;
        extraScale = 1f;
        invalidate();
    }

    // Scales the rotated image down so its bounding box still fits the
    // viewport while rotating; the user's own zoom multiplies on top of it.
    private float computeFitScale(float degrees) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return 1f;
        float angle = Math.abs(degrees) % 180f;
        if (angle > 90f) angle = 180f - angle;
        if (angle < 0.5f) return 1f;
        float sw = getSWidth();
        float sh = getSHeight();
        if (sw <= 0 || sh <= 0) return 1f;
        float fit = Math.min(w / sw, h / sh);
        float fitW = sw * fit;
        float fitH = sh * fit;
        double rad = Math.toRadians(angle);
        double cos = Math.abs(Math.cos(rad));
        double sin = Math.abs(Math.sin(rad));
        float bboxW = (float) (fitW * cos + fitH * sin);
        float bboxH = (float) (fitW * sin + fitH * cos);
        return Math.min(1f, Math.min(w / bboxW, h / bboxH));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (extraRotation != 0f) {
            int w = getWidth();
            int h = getHeight();
            canvas.save();
            canvas.rotate(extraRotation, w / 2f, h / 2f);
            if (extraScale != 1f) {
                canvas.scale(extraScale, extraScale, w / 2f, h / 2f);
            }
            super.onDraw(canvas);
            canvas.restore();
        } else {
            super.onDraw(canvas);
        }
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
        if (getScale() > getMinScale() && !org.otacoo.chan.core.settings.ChanSettings.swipeWhileZoomedIn.get()) {
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
