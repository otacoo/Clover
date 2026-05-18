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

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.Chan.injector;

import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.cache.FileCacheDownloader;
import org.otacoo.chan.core.cache.FileCacheListener;
import org.otacoo.chan.core.cache.FileCacheProvider;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.github.penfeizhou.animation.apng.APNGDrawable;
import com.github.penfeizhou.animation.loader.FileLoader;

import pl.droidsonroids.gif.GifDrawable;
import pl.droidsonroids.gif.GifImageView;

@UnstableApi
public class MultiImageView extends FrameLayout implements View.OnClickListener, DefaultLifecycleObserver {
    public enum Mode {
        UNLOADED, LOWRES, BIGIMAGE, GIF, MOVIE, OTHER
    }

    private static final String TAG = "MultiImageView";
    //for checkstyle to not be dumb about local final vars
    private static final int BACKGROUND_COLOR = Color.argb(255, 211, 217, 241);
    private static final float[] CYCLE_SPEED_VALUES = {0.5f, 0.75f, 1.0f, 1.5f, 2.0f};

    @Inject
    FileCache fileCache;

    private final ImageView playView;

    private PostImage postImage;
    private Callback callback;
    private Mode mode = Mode.UNLOADED;

    private boolean hasContent = false;
    private Call thumbnailCall;
    private FileCacheDownloader bigImageRequest;
    private FileCacheDownloader gifRequest;
    private FileCacheDownloader videoRequest;

    private VideoView videoView;
    private PlayerView exoPlayerView;
    private View playerRoot;
    private boolean videoError = false;
    private int vp9FallbackStage = 0; // 0=initial, 1=libvpx retry, 2=c2.android retry
    private ExoPlayer exoPlayer;
    private android.webkit.WebView fallbackWebView;

    private View playerControllerContainer;
    private View playerController;
    private ImageButton playerPlayPause;
    private SeekBar playerSeekBar;
    private TextView playerPosition;
    private TextView playerDuration;
    private TextView playerPlaybackSpeed;
    private ImageButton playerMute;
    private ImageButton playerBack;
    private ImageButton playerDownload;
    private View playerTopController;

    private boolean isMuted = false;

    private LifecycleOwner attachedLifecycleOwner;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateTimeTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    private boolean backgroundToggle;
    private GestureDetector gestureDetector;

    public MultiImageView(Context context) {
        this(context, null);
    }

    public MultiImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("this-escape")
    public MultiImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        inject(this);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (Math.abs(velocityY) > Math.abs(velocityX) * 1.5f && Math.abs(velocityY) > 1000) {
                    boolean isUp = velocityY < 0;
                    org.otacoo.chan.core.settings.ChanSettings.SwipeGesture closeGesture = org.otacoo.chan.core.settings.ChanSettings.swipeToClose.get();
                    org.otacoo.chan.core.settings.ChanSettings.SwipeGesture saveGesture = org.otacoo.chan.core.settings.ChanSettings.swipeToSave.get();
                    if (isUp && closeGesture == org.otacoo.chan.core.settings.ChanSettings.SwipeGesture.UP) {
                        if (callback != null) callback.onSwipeToClose(MultiImageView.this);
                        return true;
                    } else if (!isUp && closeGesture == org.otacoo.chan.core.settings.ChanSettings.SwipeGesture.DOWN) {
                        if (callback != null) callback.onSwipeToClose(MultiImageView.this);
                        return true;
                    } else if (isUp && saveGesture == org.otacoo.chan.core.settings.ChanSettings.SwipeGesture.UP) {
                        if (callback != null) callback.onSwipeToClose(MultiImageView.this);
                        return true;
                    } else if (!isUp && saveGesture == org.otacoo.chan.core.settings.ChanSettings.SwipeGesture.DOWN) {
                        if (callback != null) callback.onSwipeToSave(MultiImageView.this);
                        return true;
                    }
                }
                return false;
            }
        });

        setOnClickListener(this);

        playView = new ImageView(getContext());
        playView.setVisibility(View.GONE);
        playView.setImageResource(R.drawable.ic_play_circle_outline_white_48dp);
        addView(playView, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void bindPostImage(PostImage postImage, Callback callback) {
        this.postImage = postImage;
        this.callback = callback;

        playView.setVisibility(postImage.type == PostImage.Type.MOVIE ? View.VISIBLE : View.GONE);
    }

    public PostImage getPostImage() {
        return postImage;
    }

    public void setMode(final Mode newMode, boolean center) {
        if (this.mode != newMode) {
            cleanup();
            if (postImage != null && postImage.type == PostImage.Type.MOVIE) {
                playView.setVisibility(View.VISIBLE);
            }
            this.mode = newMode;

            AndroidUtils.waitForMeasure(this, new AndroidUtils.OnMeasuredCallback() {
                @Override
                public boolean onMeasured(View view) {
                    switch (newMode) {
                        case LOWRES:
                            boolean useFullSize = org.otacoo.chan.core.settings.ChanSettings.loadFullSizeThumbnails.get().shouldLoad()
                                    && postImage.imageUrl != null
                                    && postImage.type != org.otacoo.chan.core.model.PostImage.Type.MOVIE
                                    && postImage.type != org.otacoo.chan.core.model.PostImage.Type.SWF;
                            String url;
                            if (useFullSize) {
                                url = postImage.imageUrl.toString();
                            } else {
                                okhttp3.HttpUrl thumbUrl = postImage.getThumbnailUrl();
                                url = thumbUrl != null ? thumbUrl.toString() : null;
                            }
                            setThumbnail(url, center);
                            break;
                        case BIGIMAGE:
                            setBigImage(postImage.imageUrl.toString());
                            break;
                        case GIF:
                            setGif(postImage.imageUrl.toString());
                            break;
                        case MOVIE:
                            setVideo(postImage.imageUrl.toString());
                            break;
                        case OTHER:
                            setOther(postImage.imageUrl.toString());
                            break;
                    }
                    return true;
                }
            });
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public CustomScaleImageView findScaleImageView() {
        CustomScaleImageView bigImage = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CustomScaleImageView) {
                bigImage = (CustomScaleImageView) getChildAt(i);
            }
        }
        return bigImage;
    }

    public boolean isZoomed() {
        CustomScaleImageView scaleImageView = findScaleImageView();
        if (scaleImageView != null) {
            return scaleImageView.getScale() > scaleImageView.getMinScale() + 0.01f;
        }
        return false;
    }

    public GifImageView findGifImageView() {
        GifImageView gif = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof GifImageView) {
                gif = (GifImageView) getChildAt(i);
            }
        }
        return gif;
    }

    public ImageView findAnimatedImageView() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ImageView && !(child instanceof GifImageView)) {
                ImageView iv = (ImageView) child;
                if (iv != playView && iv.getDrawable() instanceof APNGDrawable) {
                    return iv;
                }
            }
        }
        return null;
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (exoPlayer != null) {
            exoPlayer.pause();
            if (playerPlayPause != null) {
                playerPlayPause.setImageResource(R.drawable.ic_play_circle_filled_white);
            }
        } else if (videoView != null) {
            videoView.pause();
        }
    }

    public void setVolume(boolean muted) {
        this.isMuted = muted;
        if (exoPlayer != null) {
            exoPlayer.setVolume(muted ? 0f : 1f);
        }
        updateMuteButtonIcon();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!isZoomed()) {
            if (gestureDetector.onTouchEvent(ev)) {
                ev.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(ev);
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onClick(View v) {
        if (playerControllerContainer != null && playerControllerContainer.getVisibility() == View.VISIBLE) {
            playerControllerContainer.setVisibility(View.GONE);
        } else if (playerControllerContainer != null) {
            playerControllerContainer.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideControllerTask);
            handler.postDelayed(hideControllerTask, ChanSettings.videoPlayerTimeout.get() * 1000L);
        }

        callback.onTap(this);
    }

    private final Runnable hideControllerTask = () -> {
        if (playerControllerContainer != null) {
            playerControllerContainer.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedLifecycleOwner = ViewTreeLifecycleOwner.get(this);
        if (attachedLifecycleOwner != null) {
            attachedLifecycleOwner.getLifecycle().addObserver(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (attachedLifecycleOwner != null) {
            attachedLifecycleOwner.getLifecycle().removeObserver(this);
            attachedLifecycleOwner = null;
        }
        cleanup();
    }

    private void setThumbnail(String thumbnailUrl, boolean center) {
        if (thumbnailUrl == null) {
            AndroidUtils.runOnUiThread(() -> {
                if (isAttachedToWindow()) {
                    onModeLoaded(Mode.LOWRES, new ImageView(getContext()));
                }
            });
            return;
        }

        android.graphics.Bitmap cached = ThumbnailView.getCachedBitmap(thumbnailUrl);
        if (cached != null) {
            AndroidUtils.runOnUiThread(() -> {
                if (isAttachedToWindow()) {
                    ImageView thumbnail = new ImageView(getContext());
                    thumbnail.setImageBitmap(cached);
                    onModeLoaded(Mode.LOWRES, thumbnail);
                }
            });
            return;
        }

        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (thumbnailCall != null) {
            return;
        }

        OkHttpClient client = injector().instance(OkHttpClient.class);
        Request request = new Request.Builder().url(thumbnailUrl).build();
        thumbnailCall = client.newCall(request);
        thumbnailCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                thumbnailCall = null;
                if (center) {
                    AndroidUtils.runOnUiThread(() -> {
                        if (isAttachedToWindow()) {
                            onError(e);
                        }
                    });
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (response.isSuccessful()) {
                        try (ResponseBody body = response.body()) {
                            if (body != null) {
                                Bitmap bitmap;
                                try (java.io.InputStream stream = body.byteStream()) {
                                    bitmap = BitmapFactory.decodeStream(stream);
                                }
                                if (bitmap != null && (!hasContent || mode == Mode.LOWRES)) {
                                    AndroidUtils.runOnUiThread(() -> {
                                        if (isAttachedToWindow()) {
                                            ImageView thumbnail = new ImageView(getContext());
                                            thumbnail.setImageBitmap(bitmap);
                                            onModeLoaded(Mode.LOWRES, thumbnail);
                                        }
                                    });
                                }
                            }
                        }
                    }
                } finally {
                    thumbnailCall = null;
                }
            }
        });
    }

    private void setBigImage(String imageUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading big image");
            return;
        }

        android.graphics.Bitmap cached = ThumbnailView.getCachedBitmap(imageUrl);
        if (cached != null) {
            AndroidUtils.runOnUiThread(() -> {
                if (!isAttachedToWindow()) return;
                CustomScaleImageView existing = null;
                for (int i = 0; i < getChildCount(); i++) {
                    if (getChildAt(i) instanceof CustomScaleImageView) {
                        existing = (CustomScaleImageView) getChildAt(i);
                        break;
                    }
                }
                if (existing != null) {
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, existing);
                } else {
                    final CustomScaleImageView image = new CustomScaleImageView(getContext());
                    image.setImage(com.davemorrissey.labs.subscaleview.ImageSource.bitmap(cached).tiling(false));
                    image.setOnClickListener(MultiImageView.this);
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, image);
                }
            });
            return;
        }

        if (bigImageRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        bigImageRequest = fileCache.downloadFile(imageUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                setBigImageFile(file);
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onEnd() {
                bigImageRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setBigImageFile(File file) {
        // Intercept APNG files (often served with .png extension)
        if (isApng(file)) {
            setAnimatedImageFile(file, Mode.BIGIMAGE);
            return;
        }
        setBitImageFileInternal(file, true, Mode.BIGIMAGE);
    }

    private void setGif(String gifUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (gifRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        gifRequest = fileCache.downloadFile(gifUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.GIF) {
                    setGifFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onEnd() {
                gifRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setGifFile(File file) {
        if (postImage != null && postImage.type == PostImage.Type.ANIMATED) {
            setAnimatedImageFile(file, Mode.GIF);
        } else {
            setGifDrawableFile(file);
        }
    }

    // Decode an APNG file using the APNG4Android library
    private void setAnimatedImageFile(File file, Mode fallbackMode) {
        try {
            APNGDrawable drawable = new APNGDrawable(
                    new FileLoader(file.getAbsolutePath()));

            ImageView view = new ImageView(getContext());
            view.setImageDrawable(drawable);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            onModeLoaded(Mode.GIF, view);
            drawable.start();
        } catch (Exception e) {
            e.printStackTrace();
            boolean tiling = fallbackMode == Mode.BIGIMAGE;
            setBitImageFileInternal(file, tiling, fallbackMode);
        }
    }

    private void setGifDrawableFile(File file) {
        // Decode on a background thread, then post view creation back to main.
        new Thread(() -> {
            GifDrawable drawable;
            try {
                drawable = new GifDrawable(file.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
                AndroidUtils.runOnUiThread(() -> {
                    if (isAttachedToWindow()) {
                        onError(new Exception(e.getMessage()));
                    }
                });
                return;
            } catch (OutOfMemoryError e) {
                Runtime.getRuntime().gc();
                e.printStackTrace();
                AndroidUtils.runOnUiThread(() -> {
                    if (isAttachedToWindow()) {
                        onOutOfMemoryError();
                    }
                });
                return;
            }

            // For single-frame GIFs use the scaling image viewer instead.
            if (drawable.getNumberOfFrames() == 1) {
                drawable.recycle();
                AndroidUtils.runOnUiThread(() -> {
                    if (isAttachedToWindow()) {
                        setBitImageFileInternal(file, false, Mode.GIF);
                    }
                });
                return;
            }

            // All view operations must happen on the main thread.
            final GifDrawable finalDrawable = drawable;
            AndroidUtils.runOnUiThread(() -> {
                if (isAttachedToWindow()) {
                    GifImageView view = new GifImageView(getContext());
                    view.setImageDrawable(finalDrawable);
                    onModeLoaded(Mode.GIF, view);
                } else {
                    finalDrawable.recycle();
                }
            });
        }, "gif-decode").start();
    }

    private void setVideo(String videoUrl) {
        if (videoRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        videoRequest = fileCache.downloadFile(videoUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.MOVIE) {
                    setVideoFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError(new Exception());
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                videoRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setOther(String fileUrl) {
        AndroidUtils.showThemedSnackbar(this, R.string.file_not_viewable, Snackbar.LENGTH_LONG);
    }

    private void setVideoFile(final File file) {
        if (ChanSettings.videoOpenExternal.get()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri fileUri = FileCacheProvider.getUriForFile(file);
            intent.setDataAndType(fileUri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri(null, fileUri));
            AndroidUtils.openIntent(intent);
            onModeLoaded(Mode.MOVIE, null);
            return;
        }

        // Attach the player view first so its Surface exists before ExoPlayer starts
        View root = LayoutInflater.from(getContext()).inflate(R.layout.clover_player_view, this, false);
        exoPlayerView = root.findViewById(R.id.exo_player_view);
        playerControllerContainer = root.findViewById(R.id.player_controller_container);
        playerController = root.findViewById(R.id.player_controller);
        setupPlayerController();
        playView.setVisibility(View.GONE);
        playerRoot = root;
        vp9FallbackStage = 0;
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(root, 0, lp);

        isMuted = ChanSettings.videoDefaultMuted.get();
        startExoPlayer(file);
        callback.onVideoLoaded(this);
    }

    // Builds and starts an ExoPlayer instance attached to the already-inflated exoPlayerView.
    // For DEFAULT: stage 0 = hardware first, stage 1 = libvpx preferred, stage 2 = c2.android only.
    // For PREFER:  stage 0 = libvpx preferred, stage 1 = c2.android only.
    // For OFF:     no fallback, hardware only.
    private void startExoPlayer(File file) {
        // Filters VP9 to software Codec2 decoders that support all profiles including 10-bit.
        // Includes c2.android.* (AOSP) and c2.qti.vp9.decoder (Qualcomm software Codec2).
        // Excludes OEM hardware decoders that may not support Profile 2.
        final MediaCodecSelector c2AndroidVp9Selector =
                (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                    if (MimeTypes.VIDEO_VP9.equals(mimeType)) {
                        List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                        List<MediaCodecInfo> filtered = new ArrayList<>();
                        for (MediaCodecInfo info : all) {
                            if (info.name.startsWith("c2.android.")
                                    || info.name.equals("c2.qti.vp9.decoder")) filtered.add(info);
                        }
                        return filtered;
                    }
                    return MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                };

        ChanSettings.Vp9ExtensionMode extMode = ChanSettings.vp9ExtensionMode.get();
        int rendererMode;
        MediaCodecSelector selector = MediaCodecSelector.DEFAULT;

        if (extMode == ChanSettings.Vp9ExtensionMode.OFF) {
            // OFF: hardware only, libvpx disabled, no fallback.
            rendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
        } else if ((extMode == ChanSettings.Vp9ExtensionMode.DEFAULT && vp9FallbackStage >= 2)
                || (extMode == ChanSettings.Vp9ExtensionMode.PREFER  && vp9FallbackStage >= 1)) {
            // Final fallback: c2.android.* or c2.qti.vp9.decoder (all VP9 profiles, no OEM hardware)
            rendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
            selector = c2AndroidVp9Selector;
        } else if (extMode == ChanSettings.Vp9ExtensionMode.PREFER || vp9FallbackStage >= 1) {
            // PREFER primary, or DEFAULT first retry: prefer libvpx extension over hardware.
            rendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        } else {
            // DEFAULT stage 0: hardware first, libvpx available as extension fallback.
            rendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getContext())
                .setExtensionRendererMode(rendererMode)
                .setMediaCodecSelector(selector)
                .setEnableDecoderFallback(true);

        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(getContext(), extractorsFactory);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .build();

        exoPlayer = new ExoPlayer.Builder(new NoMusicServiceCommandContext(getContext()), renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build();

        exoPlayerView.setPlayer(exoPlayer);
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    AndroidUtils.runOnUiThread(() -> {
                        if (isAttachedToWindow() && exoPlayer != null) {
                            onModeLoaded(Mode.MOVIE, playerRoot);
                            checkAudioTracks();
                        }
                    });
                } else if (playbackState == Player.STATE_ENDED) {
                    AndroidUtils.runOnUiThread(() -> {
                        if (isAttachedToWindow() && exoPlayer != null) {
                            if (ChanSettings.videoAutoLoop.get()) {
                                exoPlayer.seekTo(0);
                                exoPlayer.play();
                            } else {
                                if (playerPlayPause != null) {
                                    playerPlayPause.setImageResource(R.drawable.ic_play_circle_filled_white);
                                }
                            }
                        }
                    });
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                AndroidUtils.runOnUiThread(() -> {
                    if (isAttachedToWindow() && exoPlayer != null) {
                        if (playerPlayPause != null) {
                            playerPlayPause.setImageResource(isPlaying
                                    ? R.drawable.ic_pause_circle_filled_white
                                    : R.drawable.ic_play_circle_filled_white);
                        }
                        if (isPlaying) {
                            handler.post(updateTimeTask);
                        } else {
                            handler.removeCallbacks(updateTimeTask);
                        }
                    }
                });
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    ChanSettings.Vp9ExtensionMode extMode = ChanSettings.vp9ExtensionMode.get();
                    int maxStage = extMode == ChanSettings.Vp9ExtensionMode.DEFAULT ? 2
                                 : extMode == ChanSettings.Vp9ExtensionMode.PREFER  ? 1
                                 : 0;
                    if (vp9FallbackStage < maxStage) {
                        vp9FallbackStage++;
                        handler.removeCallbacks(updateTimeTask);
                        handler.removeCallbacks(hideControllerTask);
                        if (exoPlayer != null) {
                            exoPlayer.release();
                            exoPlayer = null;
                        }
                        startExoPlayer(file);
                        return;
                    } else if (vp9FallbackStage == maxStage && ChanSettings.videoWebViewFallback.get()) {
                        vp9FallbackStage++;
                        handler.removeCallbacks(updateTimeTask);
                        handler.removeCallbacks(hideControllerTask);
                        if (exoPlayer != null) {
                            exoPlayer.release();
                            exoPlayer = null;
                        }
                        startWebViewPlayer(file);
                        return;
                    }
                }
                onVideoError();
            }
        });

        exoPlayer.setVolume(isMuted ? 0f : 1f);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
    }

    private void startWebViewPlayer(File file) {
        fallbackWebView = new android.webkit.WebView(getContext());
        fallbackWebView.setBackgroundColor(Color.TRANSPARENT);
        fallbackWebView.getSettings().setJavaScriptEnabled(false);
        fallbackWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        fallbackWebView.getSettings().setAllowFileAccess(true);
        fallbackWebView.getSettings().setAllowFileAccessFromFileURLs(true);
        fallbackWebView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        fallbackWebView.setWebChromeClient(new android.webkit.WebChromeClient());

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;

        Uri fileUri = Uri.fromFile(file);
        String html = "<html><head><style>" +
                "body { margin: 0; background-color: transparent; display: flex; justify-content: center; align-items: center; height: 100vh; }" +
                "video { max-width: 100%; max-height: 100%; outline: none; }" +
                "</style></head><body>" +
                "<video controls autoplay loop src=\"" + fileUri.toString() + "\"></video>" +
                "</body></html>";
        fallbackWebView.loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null);

        if (exoPlayerView != null) {
            removeView(exoPlayerView);
        }
        if (playerRoot != null) {
            removeView(playerRoot);
        }

        addView(fallbackWebView, 0, lp);
        onModeLoaded(Mode.MOVIE, fallbackWebView);

        AndroidUtils.showThemedSnackbar(this, "Unsupported video format: using WebView to decode", Snackbar.LENGTH_LONG);
    }

    private void setupPlayerController() {
        playerPlayPause = playerController.findViewById(R.id.player_play_pause);
        playerSeekBar = playerController.findViewById(R.id.player_progress);
        playerPosition = playerController.findViewById(R.id.player_position);
        playerDuration = playerController.findViewById(R.id.player_duration);
        playerPlaybackSpeed = playerController.findViewById(R.id.player_playback_speed);
        playerMute = playerControllerContainer.findViewById(R.id.player_mute);
        playerBack = playerControllerContainer.findViewById(R.id.player_back);
        playerDownload = playerControllerContainer.findViewById(R.id.player_download);
        playerTopController = playerControllerContainer.findViewById(R.id.player_top_controller);

        boolean immersive = ChanSettings.useImmersiveModeForGallery.get();
        if (playerBack != null) playerBack.setVisibility(immersive ? View.VISIBLE : View.GONE);
        if (playerDownload != null) playerDownload.setVisibility(immersive ? View.VISIBLE : View.GONE);
        updateTopControllerVisibility();

        if (playerPlayPause != null) {
            playerPlayPause.setOnClickListener(v -> {
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) {
                        exoPlayer.pause();
                    } else {
                        if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
                            exoPlayer.seekTo(0);
                        }
                        exoPlayer.play();
                    }
                }
            });
        }

        if (playerSeekBar != null) {
            playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private int pendingSeekPosition = -1;

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        pendingSeekPosition = progress;
                        if (playerPosition != null) playerPosition.setText(formatTime(progress));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    handler.removeCallbacks(updateTimeTask);
                    pendingSeekPosition = -1;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (exoPlayer != null && pendingSeekPosition >= 0) {
                        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC);
                        exoPlayer.seekTo(pendingSeekPosition);
                    }
                    pendingSeekPosition = -1;
                    handler.post(updateTimeTask);
                }
            });
        }

        View rotate = playerController.findViewById(R.id.player_rotate);
        if (rotate != null) {
            rotate.setOnClickListener(v -> {
                Context context = getContext();
                while (context instanceof ContextWrapper) {
                    if (context instanceof android.app.Activity) {
                        break;
                    }
                    context = ((ContextWrapper) context).getBaseContext();
                }
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;
                    int currentOrientation = activity.getResources().getConfiguration().orientation;
                    if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    } else {
                        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    }
                }
            });
        }

        View rew = playerController.findViewById(R.id.player_rew);
        if (rew != null) {
            rew.setOnClickListener(v -> {
                if (exoPlayer != null) {
                    exoPlayer.seekTo(Math.max(0, exoPlayer.getCurrentPosition() - 5000));
                }
            });
        }

        View ffwd = playerController.findViewById(R.id.player_ffwd);
        if (ffwd != null) {
            ffwd.setOnClickListener(v -> {
                if (exoPlayer != null) {
                    exoPlayer.seekTo(Math.min(exoPlayer.getDuration(), exoPlayer.getCurrentPosition() + 15000));
                }
            });
        }

        if (playerPlaybackSpeed != null) {
            playerPlaybackSpeed.setOnClickListener(v -> {
                if (exoPlayer == null) return;
                float currentRate = exoPlayer.getPlaybackParameters().speed;
                int index = -1;
                for (int i = 0; i < CYCLE_SPEED_VALUES.length; i++) {
                    if (Math.abs(CYCLE_SPEED_VALUES[i] - currentRate) < 0.01f) {
                        index = i;
                        break;
                    }
                }
                index = (index + 1) % CYCLE_SPEED_VALUES.length;
                float newRate = CYCLE_SPEED_VALUES[index];
                exoPlayer.setPlaybackParameters(new PlaybackParameters(newRate));
                playerPlaybackSpeed.setText(String.format(Locale.US, "%.2fx", newRate));
            });
        }

        if (playerMute != null) {
            playerMute.setOnClickListener(v -> {
                isMuted = !isMuted;
                setVolume(isMuted);
                callback.onVideoMuteClicked(this, isMuted);
            });
        }

        if (playerBack != null) {
            playerBack.setOnClickListener(v -> callback.onVideoBackClicked(this));
        }

        if (playerDownload != null) {
            playerDownload.setOnClickListener(v -> callback.onVideoDownloadClicked(this));
        }

        updateMuteButtonIcon();
    }

    private void updateTopControllerVisibility() {
        if (playerTopController != null) {
            boolean backVisible = playerBack != null && playerBack.getVisibility() == View.VISIBLE;
            boolean downloadVisible = playerDownload != null && playerDownload.getVisibility() == View.VISIBLE;
            boolean muteVisible = playerMute != null && playerMute.getVisibility() == View.VISIBLE;
            playerTopController.setVisibility(backVisible || downloadVisible || muteVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMuteButtonIcon() {
        if (playerMute != null) {
            playerMute.setImageResource(isMuted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
        }
    }

    private void updateProgress() {
        if (exoPlayer == null || playerSeekBar == null) return;
        long time = exoPlayer.getCurrentPosition();
        long length = exoPlayer.getDuration();
        if (length > 0) {
            playerSeekBar.setMax((int) length);
            playerSeekBar.setProgress((int) time);
            if (playerPosition != null) playerPosition.setText(formatTime(time));
            if (playerDuration != null) playerDuration.setText(formatTime(length));
        }
    }

    private String formatTime(long millis) {
        int seconds = (int) (millis / 1000) % 60;
        int minutes = (int) (millis / (1000 * 60)) % 60;
        int hours = (int) (millis / (1000 * 60 * 60)) % 24;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void checkAudioTracks() {
        if (exoPlayer != null) {
            boolean hasAudio = false;
            Tracks tracks = exoPlayer.getCurrentTracks();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                    break;
                }
            }

            if (hasAudio) {
                if (ChanSettings.useImmersiveModeForGallery.get() && playerMute != null) {
                    playerMute.setVisibility(View.VISIBLE);
                }
                callback.onAudioLoaded(this);
            }
        }
        updateTopControllerVisibility();
    }

    private void onVideoError() {
        if (!videoError) {
            videoError = true;
            cleanupExo();
            callback.onVideoError(this);
        }
    }

    private void cleanupVideo(VideoView videoView) {
        videoView.stopPlayback();
    }

    private void cleanupExo() {
        handler.removeCallbacks(updateTimeTask);
        handler.removeCallbacks(hideControllerTask);
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        exoPlayerView = null;
        cleanupWebView();
    }

    private void cleanupWebView() {
        if (fallbackWebView != null) {
            removeView(fallbackWebView);
            fallbackWebView.loadUrl("about:blank");
            fallbackWebView.destroy();
            fallbackWebView = null;
        }
    }

    public void toggleTransparency() {
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        ImageView animatedView = findAnimatedImageView();
        if (imageView == null && gifView == null && animatedView == null) return;
        boolean isImage = imageView != null && gifView == null && animatedView == null;
        int backgroundColor = backgroundToggle ? Color.TRANSPARENT : BACKGROUND_COLOR;
        if (isImage) {
            imageView.setTileBackgroundColor(backgroundColor);
        } else {
            ImageView targetView = gifView != null ? gifView : animatedView;
            if (targetView != null && targetView.getDrawable() != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    targetView.getDrawable().setColorFilter(new BlendModeColorFilter(backgroundColor, BlendMode.DST_OVER));
                } else {
                    android.graphics.ColorFilter legacyFilter = new android.graphics.PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.DST_OVER);
                    targetView.getDrawable().setColorFilter(legacyFilter);
                }
            }
        }
        backgroundToggle = !backgroundToggle;
    }

    public void setOrientation(int orientation) {
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        ImageView animatedView = findAnimatedImageView();
        ImageView targetAnimView = gifView != null ? gifView : animatedView;
        if (imageView == null && targetAnimView == null) return;
        boolean isImage = imageView != null && targetAnimView == null;
        if (isImage) {
            if (orientation < 0) {
                if (orientation == -1) {
                    imageView.setScaleX(-1f);
                    imageView.setScaleY(1f);
                } else {
                    imageView.setScaleX(1f);
                    imageView.setScaleY(-1f);
                }
            } else {
                imageView.setScaleX(1f);
                imageView.setScaleY(1f);
                imageView.setOrientation(orientation);

                float scale;
                if (orientation == 0 || orientation == 180)
                    scale = Math.min(getWidth() / (float) imageView.getSWidth(), getHeight() / (float) imageView.getSHeight());
                else
                    scale = Math.min(getWidth() / (float) imageView.getSHeight(), getHeight() / (float) imageView.getSWidth());
                imageView.setMinScale(scale);

                if (imageView.getMaxScale() < scale * 2f) {
                    imageView.setMaxScale(scale * 2f);
                }
            }
        } else if (targetAnimView != null) {
            if (orientation < 0) {
                if (orientation == -1) {
                    targetAnimView.setScaleX(-1f);
                    targetAnimView.setScaleY(1f);
                } else {
                    targetAnimView.setScaleX(1f);
                    targetAnimView.setScaleY(-1f);
                }
            } else {
                targetAnimView.setScaleX(1f);
                targetAnimView.setScaleY(1f);
                if (orientation == 0) {
                    targetAnimView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                } else {
                    targetAnimView.setScaleType(ImageView.ScaleType.MATRIX);
                    int iw = targetAnimView.getDrawable().getIntrinsicWidth();
                    int ih = targetAnimView.getDrawable().getIntrinsicHeight();
                    RectF dstRect = new RectF(0, 0, targetAnimView.getWidth(), targetAnimView.getHeight());
                    Matrix matrix = new Matrix();
                    if (orientation == 90 || orientation == 270) {
                        matrix.setRectToRect(new RectF(0, 0, ih, iw), dstRect, Matrix.ScaleToFit.CENTER);
                        matrix.preRotate(90f, (float) ih / 2, (float) ih / 2);
                    } else {
                        matrix.setRectToRect(new RectF(0, 0, iw, ih), dstRect, Matrix.ScaleToFit.CENTER);
                    }
                    if (orientation >= 180)
                        matrix.preRotate(180f, (float) iw / 2, (float) ih / 2);
                    targetAnimView.setImageMatrix(matrix);
                }
            }
        }
    }

    // Detect APNG by finding an 'acTL' (animation control) chunk before the first 'IDAT' chunk
    private static boolean isApng(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] sig = new byte[8];
            if (raf.read(sig) != 8) return false;
            if (sig[0] != (byte) 0x89 || sig[1] != 'P' || sig[2] != 'N' || sig[3] != 'G'
                    || sig[4] != '\r' || sig[5] != '\n' || sig[6] != 0x1A || sig[7] != '\n') {
                return false;
            }
            byte[] chunkHeader = new byte[8];
            while (raf.read(chunkHeader) == 8) {
                int length = ((chunkHeader[0] & 0xFF) << 24) | ((chunkHeader[1] & 0xFF) << 16)
                        | ((chunkHeader[2] & 0xFF) << 8) | (chunkHeader[3] & 0xFF);
                String type = new String(chunkHeader, 4, 4, StandardCharsets.US_ASCII);
                if ("acTL".equals(type)) return true;
                if ("IDAT".equals(type)) return false;
                raf.seek(raf.getFilePointer() + length + 4);
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private void setBitImageFileInternal(File file, boolean tiling, final Mode forMode) {
        final CustomScaleImageView image = new CustomScaleImageView(getContext());
        image.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(file.getAbsolutePath()).tiling(tiling));
        image.setOnClickListener(MultiImageView.this);
        addView(image, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        image.setCallback(new CustomScaleImageView.Callback() {
            @Override
            public void onReady() {
                if (!hasContent || mode == forMode) {
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, image);
                }
            }

            @Override
            public void onError(boolean wasInitial) {
                onBigImageError(wasInitial);
            }
        });
    }

    private void onError(Exception e) {
        if (!isAttachedToWindow()) return;
        String message = getContext().getString(R.string.image_preview_failed);
        String extra = e.getMessage() == null ? "" : ": " + e.getMessage();
        AndroidUtils.showThemedSnackbar(this, message + extra, Snackbar.LENGTH_SHORT);
        callback.showProgress(this, false);
    }

    private void onNotFoundError() {
        if (!isAttachedToWindow()) return;
        callback.showProgress(this, false);
        AndroidUtils.showThemedSnackbar(this, R.string.image_not_found, Snackbar.LENGTH_SHORT);
    }

    private void onOutOfMemoryError() {
        if (!isAttachedToWindow()) return;
        AndroidUtils.showThemedSnackbar(this, R.string.image_preview_failed_oom, Snackbar.LENGTH_SHORT);
        callback.showProgress(this, false);
    }

    private void onBigImageError(boolean wasInitial) {
        if (!isAttachedToWindow()) return;
        if (wasInitial) {
            AndroidUtils.showThemedSnackbar(this, R.string.image_failed_big_image, Snackbar.LENGTH_SHORT);
            callback.showProgress(this, false);
        }
    }

    public void cleanup() {
        if (thumbnailCall != null) {
            thumbnailCall.cancel();
            thumbnailCall = null;
        }
        if (bigImageRequest != null) {
            bigImageRequest.cancel();
            bigImageRequest = null;
        }
        if (gifRequest != null) {
            gifRequest.cancel();
            gifRequest = null;
        }
        if (videoRequest != null) {
            videoRequest.cancel();
            videoRequest = null;
        }

        cleanupWebView();

        // Stop all active view content
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof VideoView) {
                cleanupVideo((VideoView) child);
            } else if (child instanceof GifImageView) {
                GifImageView gif = (GifImageView) child;
                if (gif.getDrawable() instanceof GifDrawable) {
                    ((GifDrawable) gif.getDrawable()).stop();
                }
            } else if (child instanceof ImageView && child != playView) {
                Drawable d = ((ImageView) child).getDrawable();
                if (d instanceof APNGDrawable) {
                    ((APNGDrawable) d).stop();
                }
            }
        }

        cleanupExo();
    }

    private void onModeLoaded(Mode mode, View view) {
        if (!isAttachedToWindow()) {
            if (view instanceof GifImageView) {
                GifImageView gif = (GifImageView) view;
                if (gif.getDrawable() instanceof GifDrawable) {
                    ((GifDrawable) gif.getDrawable()).recycle();
                }
            } else if (view instanceof ImageView) {
                Drawable d = ((ImageView) view).getDrawable();
                if (d instanceof APNGDrawable) {
                    ((APNGDrawable) d).stop();
                }
            }
            return;
        }

        if (view != null) {
            // Remove all other views
            boolean alreadyAttached = false;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child != playView) {
                    if (child != view) {
                        if (child instanceof VideoView) {
                            cleanupVideo((VideoView) child);
                        }

                        removeViewAt(i);
                    } else {
                        alreadyAttached = true;
                    }
                }
            }

            if (!alreadyAttached) {
                addView(view, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }

        hasContent = true;
        callback.onModeLoaded(this, mode);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        if (child instanceof GifImageView gif) {
            if (gif.getDrawable() instanceof GifDrawable drawable) {
                if (drawable.getFrameByteCount() > 100 * 1024 * 1024) { //max size from RecordingCanvas
                    onError(new Exception("Uncompressed GIF too large (>100MB)"));
                    return false;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public interface Callback {
        void onSwipeToClose(MultiImageView multiImageView);

        void onSwipeToSave(MultiImageView multiImageView);

        void onTap(MultiImageView multiImageView);

        void showProgress(MultiImageView multiImageView, boolean progress);

        void onProgress(MultiImageView multiImageView, long current, long total);

        void onVideoError(MultiImageView multiImageView);

        void onVideoLoaded(MultiImageView multiImageView);

        void onModeLoaded(MultiImageView multiImageView, Mode mode);

        void onAudioLoaded(MultiImageView multiImageView);

        void onVideoMuteClicked(MultiImageView multiImageView, boolean muted);

        void onVideoBackClicked(MultiImageView multiImageView);

        void onVideoDownloadClicked(MultiImageView multiImageView);
    }

    public static class NoMusicServiceCommandContext extends ContextWrapper {
        public NoMusicServiceCommandContext(Context base) {
            super(base);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // Only allow broadcasts when it's not a music service command
            // Prevents pause intents from broadcasting
            if (!"com.android.music.musicservicecommand".equals(intent.getAction())) {
                super.sendBroadcast(intent);
            }
        }
    }
}
