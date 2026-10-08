/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

// A few faint snow dots drifting down, shown with the Christmas theme.
public class SnowView extends View {
    private static final int FLAKES = 14;
    private static final long FRAME_MS = 50;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] x = new float[FLAKES];
    private final float[] y = new float[FLAKES];
    private final float[] radius = new float[FLAKES];
    private final float[] speed = new float[FLAKES];
    private final float[] drift = new float[FLAKES];
    private final int[] alpha = new int[FLAKES];
    private boolean seeded = false;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
            invalidate();
            if (isAttachedToWindow()) {
                handler.postDelayed(this, FRAME_MS);
            }
        }
    };

    public SnowView(Context context) {
        this(context, null);
    }

    public SnowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.WHITE);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        seed(w, h);
    }

    private void seed(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < FLAKES; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
            radius[i] = (1.5f + random.nextFloat() * 2.5f) * density;
            speed[i] = (0.8f + random.nextFloat() * 1.8f) * density;
            drift[i] = (random.nextFloat() - 0.5f) * density;
            // Low opacity snow
            alpha[i] = 30 + random.nextInt(40);
        }
        seeded = true;
    }

    private void step() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!seeded) {
            seed(w, h);
            return;
        }
        for (int i = 0; i < FLAKES; i++) {
            y[i] += speed[i];
            x[i] += drift[i] + (float) Math.sin((y[i] / h) * Math.PI * 2) * 0.4f;
            if (y[i] - radius[i] > h) {
                y[i] = -radius[i];
                x[i] = random.nextFloat() * w;
            }
            if (x[i] < -radius[i]) {
                x[i] = w + radius[i];
            } else if (x[i] > w + radius[i]) {
                x[i] = -radius[i];
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!seeded) return;
        for (int i = 0; i < FLAKES; i++) {
            paint.setAlpha(alpha[i]);
            canvas.drawCircle(x[i], y[i], radius[i], paint);
        }
    }
}
