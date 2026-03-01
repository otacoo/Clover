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
package org.otacoo.chan.ui.dialog;

import static org.otacoo.chan.utils.AndroidUtils.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

public class ColorPickerView extends View {
    private static final int[] COLORS = new int[]{
            0xFFFF0000, 0xFFFF00FF, 0xFF0000FF, 0xFF00FFFF, 0xFF00FF00,
            0xFFFFFF00, 0xFFFF0000
    };

    private Paint paint;
    private Paint centerPaint;
    private Paint valuePaint;
    private int centerRadius;
    private OnColorChangedListener listener;

    private float[] hsv = new float[3];
    private RectF valueRect = new RectF();
    private boolean trackingValue = false;

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    public ColorPickerView(Context context) {
        super(context);

        centerRadius = dp(32);

        Shader s = new SweepGradient(0, 0, COLORS, null);

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(s);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(32));

        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setStrokeWidth(dp(5));

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        Color.colorToHSV(Color.BLUE, hsv);
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        float[] newHsv = new float[3];
        Color.colorToHSV(color, newHsv);
        
        // For achromatic colors (very low saturation), preserve the existing hue
        // This prevents the gradient bar from always showing red when picking dark/grey colors
        if (newHsv[1] < 0.1f) {
            newHsv[0] = hsv[0];
        }
        
        hsv = newHsv;
        centerPaint.setColor(color);
        invalidate();
    }

    public int getColor() {
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        
        int minSize = dp(200);
        int targetSize = dp(300);
        
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            w = Math.min(targetSize, w);
        } else if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            w = targetSize;
        }
        
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            h = Math.min(targetSize, h);
        } else if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            h = targetSize;
        }
        
        w = Math.max(w, minSize);
        h = Math.max(h, minSize);
        
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        float cx = getWidth() / 2f;
        float cy = (getHeight() - dp(48)) / 2f; 

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                trackingValue = valueRect.contains(x, y);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (trackingValue) {
                    float val = (x - valueRect.left) / valueRect.width();
                    val = Math.max(0f, Math.min(1f, val));
                    if (val < 0.5f) {
                        hsv[1] = 1f;
                        hsv[2] = val * 2f;
                    } else {
                        hsv[1] = 1f - (val - 0.5f) * 2f;
                        hsv[2] = 1f;
                    }
                } else {
                    float dx = x - cx;
                    float dy = y - cy;
                    float angle = (float) Math.atan2(dy, dx);
                    float unit = (float) (angle / (2.0 * Math.PI));
                    if (unit < 0.0) {
                        unit += 1.0;
                    }
                    int color = interpColor(COLORS, unit);
                    float[] tempHsv = new float[3];
                    Color.colorToHSV(color, tempHsv);
                    hsv[0] = tempHsv[0];
                    if (hsv[1] < 0.1f) hsv[1] = 1f;
                    if (hsv[2] < 0.1f) hsv[2] = 1f;
                }
                
                int finalColor = Color.HSVToColor(hsv);
                centerPaint.setColor(finalColor);
                if (listener != null) {
                    listener.onColorChanged(finalColor);
                }
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                trackingValue = false;
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = (getHeight() - dp(48)) / 2f;
        
        float r = Math.min(cx, cy) - paint.getStrokeWidth() * 0.5f - dp(8);
        
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawOval(new RectF(-r, -r, r, r), paint);
        canvas.drawCircle(0, 0, centerRadius, centerPaint);
        canvas.restore();
        
        // Draw value bar (Black -> Color -> White)
        float margin = dp(16);
        float barHeight = dp(24);
        valueRect.set(margin, getHeight() - barHeight - margin, getWidth() - margin, getHeight() - margin);
        
        int middleColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        int[] gradientColors = new int[] { Color.BLACK, middleColor, Color.WHITE };
        
        valuePaint.setShader(new LinearGradient(valueRect.left, 0, valueRect.right, 0, gradientColors, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(valueRect, dp(4), dp(4), valuePaint);
        
        // Draw indicator on value bar
        Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setColor(Color.WHITE);
        indicatorPaint.setStyle(Paint.Style.STROKE);
        indicatorPaint.setStrokeWidth(dp(2));
        
        float barVal;
        if (hsv[2] < 1f) {
            barVal = hsv[2] / 2f;
        } else {
            barVal = 0.5f + (1f - hsv[1]) / 2f;
        }
        
        float ix = valueRect.left + barVal * valueRect.width();
        canvas.drawCircle(ix, valueRect.centerY(), barHeight / 2f + dp(2), indicatorPaint);
        indicatorPaint.setColor(Color.BLACK);
        canvas.drawCircle(ix, valueRect.centerY(), barHeight / 2f + dp(4), indicatorPaint);
    }

    private int interpColor(int colors[], float unit) {
        if (unit <= 0) {
            return colors[0];
        }
        if (unit >= 1) {
            return colors[colors.length - 1];
        }

        float p = unit * (colors.length - 1);
        int i = (int) p;
        p -= i;

        int c0 = colors[i];
        int c1 = colors[i + 1];
        int a = ave(Color.alpha(c0), Color.alpha(c1), p);
        int r = ave(Color.red(c0), Color.red(c1), p);
        int g = ave(Color.green(c0), Color.green(c1), p);
        int b = ave(Color.blue(c0), Color.blue(c1), p);

        return Color.argb(a, r, g, b);
    }

    private int ave(int s, int d, float p) {
        return s + Math.round(p * (d - s));
    }
}
