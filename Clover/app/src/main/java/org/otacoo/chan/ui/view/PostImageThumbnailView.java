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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.cache.FileCacheListener;
import org.otacoo.chan.core.cache.FileCacheProvider;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.AndroidUtils;

import java.io.File;

public class PostImageThumbnailView extends ThumbnailView implements View.OnLongClickListener {
    private PostImage postImage;
    private final Drawable playIcon;
    private final Rect bounds = new Rect();
    private float ratio = 0f;

    public PostImageThumbnailView(Context context) {
        this(context, null);
    }

    public PostImageThumbnailView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("this-escape")
    public PostImageThumbnailView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setOnLongClickListener(this);

        playIcon = ContextCompat.getDrawable(context, R.drawable.ic_play_circle_outline_white_24dp);
    }

    public void setPostImage(PostImage postImage, int width, int height) {
        setPostImage(postImage, width, height, false);
    }

    public void setPostImage(PostImage postImage, int width, int height, boolean cacheOnly) {
        if (this.postImage != postImage) {
            this.postImage = postImage;

            if (postImage != null) {
                boolean useFullSize = ChanSettings.loadFullSizeThumbnails.get().shouldLoad()
                        && postImage.imageUrl != null
                        && postImage.type != PostImage.Type.MOVIE
                        && postImage.type != PostImage.Type.SWF;
                String url;
                if (useFullSize) {
                    url = postImage.imageUrl.toString();
                } else {
                    okhttp3.HttpUrl thumbUrl = postImage.getThumbnailUrl();
                    url = thumbUrl != null ? thumbUrl.toString() : null;
                }
                setUrl(url, width, height, cacheOnly);
            } else {
                setUrl(null, width, height, cacheOnly);
            }
        }
    }

    public void setRatio(float ratio) {
        this.ratio = ratio;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        super.draw(canvas);

        if (postImage != null && postImage.type == PostImage.Type.MOVIE && !error) {
            int x = (int) (getWidth() / 2.0 - playIcon.getIntrinsicWidth() / 2.0);
            int y = (int) (getHeight() / 2.0 - playIcon.getIntrinsicHeight() / 2.0);

            bounds.set(x, y, x + playIcon.getIntrinsicWidth(), y + playIcon.getIntrinsicHeight());
            playIcon.setBounds(bounds);
            playIcon.draw(canvas);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratio == 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && (heightMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.AT_MOST)) {
                int width = MeasureSpec.getSize(widthMeasureSpec);

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (width / ratio), MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (postImage == null || postImage.imageUrl == null) {
            return false;
        }

        ClipboardManager clipboard = (ClipboardManager) AndroidUtils.getAppContext().getSystemService(Context.CLIPBOARD_SERVICE);
        boolean isImage = postImage.type != PostImage.Type.MOVIE;
        if (!ChanSettings.shareUrl.get() && isImage) {
            AndroidUtils.showThemedSnackbar(this, "Downloading image\u2026", Snackbar.LENGTH_SHORT);
            FileCache fileCache = injector().instance(FileCache.class);
            fileCache.downloadFile(postImage.imageUrl.toString(), new FileCacheListener() {
                @Override
                public void onSuccess(File file) {
                    AndroidUtils.runOnUiThread(() -> {
                        Uri uri = FileCacheProvider.getUriForFile(file);
                        ClipData clip = ClipData.newUri(AndroidUtils.getAppContext().getContentResolver(),
                                postImage.filename, uri);
                        clipboard.setPrimaryClip(clip);
                        AndroidUtils.showThemedSnackbar(PostImageThumbnailView.this, R.string.image_copied, Snackbar.LENGTH_SHORT);
                    });
                }

                @Override
                public void onFail(boolean notFound) {
                    AndroidUtils.runOnUiThread(() ->
                            copyUrlToClipboard(clipboard));
                }
            });
            return true;
        }

        copyUrlToClipboard(clipboard);
        return true;
    }

    private void copyUrlToClipboard(ClipboardManager clipboard) {
        ClipData clip = ClipData.newPlainText("File URL", postImage.imageUrl.toString());
        clipboard.setPrimaryClip(clip);
        AndroidUtils.showThemedSnackbar(this, R.string.url_text_copied, Snackbar.LENGTH_SHORT);
    }
}
