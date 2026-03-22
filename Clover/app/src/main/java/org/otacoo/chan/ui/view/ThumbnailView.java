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

import static org.otacoo.chan.Chan.injector;
import static org.otacoo.chan.utils.AndroidUtils.getString;
import static org.otacoo.chan.utils.AndroidUtils.sp;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.otacoo.chan.R;
import org.otacoo.chan.utils.AndroidUtils;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ThumbnailView extends View {
    private static final String TAG = "ThumbnailView";
    private static final LruCache<String, Bitmap> sMemoryCache;

    static {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        sMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    @Nullable
    public static Bitmap getCachedBitmap(String url) {
        if (TextUtils.isEmpty(url)) return null;
        return sMemoryCache.get(url);
    }

    public static void putBitmap(String url, Bitmap bitmap) {
        if (TextUtils.isEmpty(url) || bitmap == null) return;
        sMemoryCache.put(url, bitmap);
    }

    private Call currentCall;
    private String currentUrl;
    private boolean hidden = false;

    private boolean circular = false;
    private int rounding = 0;
    private boolean clickable = false;

    private boolean calculate;
    private Bitmap bitmap;
    private RectF bitmapRect = new RectF();
    private RectF drawRect = new RectF();
    private RectF outputRect = new RectF();

    private Matrix matrix = new Matrix();
    BitmapShader bitmapShader;
    private Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private boolean foregroundCalculate = false;
    private Drawable foreground;

    protected boolean error = false;
    private String errorText;
    private String labelText;
    private Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Rect tmpTextRect = new Rect();

    public ThumbnailView(Context context) {
        super(context);
        init();
    }

    public ThumbnailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ThumbnailView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        textPaint.setColor(0xff000000);
        textPaint.setTextSize(sp(14));
        textPaint.setTextAlign(Paint.Align.CENTER);
        backgroundPaint.setColor(0x22000000);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelRequest();
    }

    public void setUrl(String url, int width, int height) {
        if (TextUtils.equals(currentUrl, url)) {
            // Skip only if the bitmap is already displayed or a request is in flight.
            if (bitmap != null || currentCall != null) {
                return;
            }
            error = false;
        } else {
            cancelRequest();
            currentUrl = url;
            error = false;
            setImageBitmap(null);
        }

        if (TextUtils.isEmpty(url)) {
            return;
        }

        Bitmap cached = sMemoryCache.get(url);
        if (cached != null) {
            setImageBitmap(cached);
            onImageSet();
            return;
        }

        OkHttpClient client = injector().instance(OkHttpClient.class);
        Request request = new Request.Builder()
                .url(url)
                .build();

        currentCall = client.newCall(request);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled()) return;
                AndroidUtils.runOnUiThread(() -> {
                    error = true;
                    errorText = getString(R.string.thumbnail_load_failed_network);
                    onImageSet();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (call.isCanceled()) {
                    response.close();
                    return;
                }

                if (!response.isSuccessful()) {
                    AndroidUtils.runOnUiThread(() -> {
                        error = true;
                        errorText = getString(R.string.thumbnail_load_failed_server);
                        onImageSet();
                    });
                    response.close();
                    return;
                }

                try (ResponseBody body = response.body()) {
                    if (body == null) throw new IOException("Empty body");

                    byte[] data = body.bytes();

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, width, height);

                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    
                    if (bitmap != null) {
                        sMemoryCache.put(url, bitmap);
                        AndroidUtils.runOnUiThread(() -> {
                            setImageBitmap(bitmap);
                            onImageSet();
                        });
                    } else {
                        AndroidUtils.runOnUiThread(() -> {
                            error = true;
                            errorText = getString(R.string.thumbnail_load_failed_server);
                            onImageSet();
                        });
                    }
                } catch (IOException e) {
                    if (!call.isCanceled()) {
                        throw e;
                    }
                }
            }
        });
    }

    private static int calcSampleSize(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1;
        int s = 1;
        while ((srcW / (s * 2)) >= dstW && (srcH / (s * 2)) >= dstH) s *= 2;
        return s;
    }

    private void cancelRequest() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }

    public void setCircular(boolean circular) {
        this.circular = circular;
    }

    public void setRounding(int rounding) {
        this.rounding = rounding;
    }

    public int getRounding() {
        return rounding;
    }

    @SuppressWarnings({"deprecation", "ConstantConditions"})
    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);

        if (clickable != this.clickable) {
            this.clickable = clickable;

            foregroundCalculate = clickable;
            if (clickable) {
                foreground = ContextCompat.getDrawable(getContext(), R.drawable.item_background);
                foreground.setCallback(this);
                if (foreground.isStateful()) {
                    foreground.setState(getDrawableState());
                }
            } else {
                unscheduleDrawable(foreground);
                foreground = null;
            }
            requestLayout();
            invalidate();
        }
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void hide(boolean animateIfNeeded) {
        hidden = true;
        setAlpha(0f);
    }

    public void show(boolean animateIfNeeded) {
        hidden = false;
        setAlpha(1f);
    }

    @Override
    protected boolean onSetAlpha(int alpha) {
        if (error) {
            textPaint.setAlpha(alpha);
        } else {
            bitmapPaint.setAlpha(alpha);
        }
        invalidate();

        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        calculate = true;
        foregroundCalculate = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();

        outputRect.set(getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());

        if (error) {
            // Render a simple text if there was an error.
            canvas.save();

            textPaint.getTextBounds(errorText, 0, errorText.length(), tmpTextRect);
            float x = outputRect.centerX();
            float y = outputRect.centerY() - tmpTextRect.exactCenterY();
            canvas.drawText(errorText, x, y, textPaint);

            canvas.restore();
        } else {
            // Gray background if thumbnail is not yet loaded and no foreground icon is set.
            if (bitmap == null && foreground == null) {
                if (circular) {
                    canvas.drawRoundRect(outputRect, width / 2.0f, height / 2.0f, backgroundPaint);
                } else {
                    canvas.drawRoundRect(outputRect, rounding, rounding, backgroundPaint);
                }
            }

            if (bitmap == null) {
                if (foreground != null) {
                    canvas.save();
                    if (foregroundCalculate) {
                        foregroundCalculate = false;
                        int p = AndroidUtils.dp(4);
                        foreground.setBounds(p, p, getWidth() - p, getHeight() - p);
                    }
                    foreground.draw(canvas);
                    canvas.restore();
                }

                if (labelText != null && labelText.length() > 0) {
                    canvas.save();
                    float x = outputRect.centerX();
                    // Vertical centering of text
                    textPaint.setColor(AndroidUtils.getAttrColor(getContext(), R.attr.text_color_secondary));
                    textPaint.setTextSize(sp(11));
                    textPaint.getTextBounds(labelText, 0, labelText.length(), tmpTextRect);
                    float y = outputRect.centerY() - (tmpTextRect.top + tmpTextRect.bottom) / 2f;
                    canvas.drawText(labelText, x, y, textPaint);
                    canvas.restore();
                }
                return;
            }

            // If needed, calculate positions.
            if (calculate) {
                calculate = false;
                bitmapRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                float scale = Math.max(
                        (float) width / (float) bitmap.getWidth(),
                        (float) height / (float) bitmap.getHeight());
                float scaledX = bitmap.getWidth() * scale;
                float scaledY = bitmap.getHeight() * scale;
                float offsetX = (scaledX - width) * 0.5f;
                float offsetY = (scaledY - height) * 0.5f;

                drawRect.set(-offsetX, -offsetY, scaledX - offsetX, scaledY - offsetY);
                drawRect.offset(getPaddingLeft(), getPaddingTop());

                matrix.setRectToRect(bitmapRect, drawRect, Matrix.ScaleToFit.FILL);

                bitmapShader.setLocalMatrix(matrix);
                bitmapPaint.setShader(bitmapShader);
            }

            canvas.save();
            canvas.clipRect(outputRect);

            // Draw rounded bitmap.
            if (circular) {
                canvas.drawRoundRect(outputRect, width / 2.0f, height / 2.0f, bitmapPaint);
            } else {
                canvas.drawRoundRect(outputRect, rounding, rounding, bitmapPaint);
            }

            canvas.restore();
            canvas.save();

            if (foreground != null) {
                if (foregroundCalculate) {
                    foregroundCalculate = false;
                    int p = AndroidUtils.dp(4);
                    foreground.setBounds(p, p, getWidth() - p, getHeight() - p);
                }

                foreground.draw(canvas);
            }

            canvas.restore();
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || (who == foreground);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();

        if (foreground != null) {
            foreground.jumpToCurrentState();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (foreground != null && foreground.isStateful()) {
            foreground.setState(getDrawableState());
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (foreground != null) {
            foreground.setHotspot(x, y);
        }
    }

    private void onImageSet() {
        clearAnimation();
        setAlpha(1f);
    }

    public void setLabelText(String text) {
        this.labelText = text;
        invalidate();
    }

    private void setImageBitmap(Bitmap bitmap) {
        bitmapShader = null;
        bitmapPaint.setShader(null);

        this.bitmap = bitmap;
        if (bitmap != null) {
            calculate = true;
            bitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        invalidate();
    }

    public void setImageDrawable(Drawable drawable) {
        cancelRequest();
        setImageBitmap(null);
        this.foreground = drawable;
        if (drawable != null) {
            drawable.setCallback(this);
            // Request a relayout/redraw to ensure setBounds is called during onDraw
        }
        foregroundCalculate = true;
        invalidate();
    }
}
