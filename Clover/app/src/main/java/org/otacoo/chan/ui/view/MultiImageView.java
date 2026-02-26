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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.davemorrissey.labs.subscaleview.ImageSource;

import org.otacoo.chan.R;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.cache.FileCacheDownloader;
import org.otacoo.chan.core.cache.FileCacheListener;
import org.otacoo.chan.core.cache.FileCacheProvider;
import org.otacoo.chan.core.di.UserAgentProvider;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import pl.droidsonroids.gif.GifDrawable;
import pl.droidsonroids.gif.GifImageView;

public class MultiImageView extends FrameLayout implements View.OnClickListener, LifecycleObserver, Player.Listener {
    public enum Mode {
        UNLOADED, LOWRES, BIGIMAGE, GIF, MOVIE, OTHER
    }

    private static final String TAG = "MultiImageView";
    //for checkstyle to not be dumb about local final vars
    private static final int BACKGROUND_COLOR = Color.argb(255, 211, 217, 241);

    @Inject
    FileCache fileCache;

    @Inject
    UserAgentProvider userAgent;

    private ImageView playView;

    private PostImage postImage;
    private Callback callback;
    private Mode mode = Mode.UNLOADED;

    private boolean hasContent = false;
    private Call thumbnailCall;
    private FileCacheDownloader bigImageRequest;
    private FileCacheDownloader gifRequest;
    private FileCacheDownloader videoRequest;

    private VideoView videoView;
    private PlayerView exoVideoView;
    private boolean videoError = false;
    private MediaPlayer mediaPlayer;
    private ExoPlayer exoPlayer;

    private boolean backgroundToggle;

    public MultiImageView(Context context) {
        this(context, null);
    }

    public MultiImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        inject(this);

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
//            Logger.test("Changing mode from " + this.mode + " to " + newMode + " for " + postImage.thumbnailUrl);
            this.mode = newMode;

            AndroidUtils.waitForMeasure(this, new AndroidUtils.OnMeasuredCallback() {
                @Override
                public boolean onMeasured(View view) {
                    switch (newMode) {
                        case LOWRES:
                            setThumbnail(postImage.getThumbnailUrl().toString(), center);
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

    public GifImageView findGifImageView() {
        GifImageView gif = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof GifImageView) {
                gif = (GifImageView) getChildAt(i);
            }
        }
        return gif;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void pauseVideo() {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        } else if (videoView != null) {
            videoView.pause();
        }
    }

    public void setVolume(boolean muted) {
        final float volume = muted ? 0f : 1f;
        if (exoPlayer != null) {
            exoPlayer.setVolume(volume);
        } else if (mediaPlayer != null) {
            mediaPlayer.setVolume(volume, volume);
        }
    }

    @Override
    public void onClick(View v) {
        callback.onTap(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ((StartActivity) getContext()).getLifecycle().addObserver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ((StartActivity) getContext()).getLifecycle().removeObserver(this);
        cancelLoad();
    }

    private void setThumbnail(String thumbnailUrl, boolean center) {
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
                    AndroidUtils.runOnUiThread(() -> onError(e));
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        byte[] data = response.body().bytes();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bitmap != null && (!hasContent || mode == Mode.LOWRES)) {
                            AndroidUtils.runOnUiThread(() -> {
                                ImageView thumbnail = new ImageView(getContext());
                                thumbnail.setImageBitmap(bitmap);
                                onModeLoaded(Mode.LOWRES, thumbnail);
                            });
                        }
                    }
                } finally {
                    response.close();
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
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                bigImageRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setBigImageFile(File file) {
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
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                gifRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setGifFile(File file) {
        GifDrawable drawable;
        try {
            drawable = new GifDrawable(file.getAbsolutePath());

            // For single frame gifs, use the scaling image instead
            // The region decoder doesn't work for gifs, so we unfortunately
            // have to use the more memory intensive non tiling mode.
            if (drawable.getNumberOfFrames() == 1) {
                drawable.recycle();
                setBitImageFileInternal(file, false, Mode.GIF);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            onError(new Exception());
            return;
        } catch (OutOfMemoryError e) {
            Runtime.getRuntime().gc();
            e.printStackTrace();
            onOutOfMemoryError();
            return;
        }

        GifImageView view = new GifImageView(getContext());
        view.setImageDrawable(drawable);
        onModeLoaded(Mode.GIF, view);
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
        Toast.makeText(getContext(), R.string.file_not_viewable, Toast.LENGTH_LONG).show();
    }

    private void setVideoFile(final File file) {
        if (ChanSettings.videoOpenExternal.get()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri fileUri = FileCacheProvider.getUriForFile(file);
            intent.setDataAndType(fileUri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri(null, fileUri));

            AndroidUtils.openIntent(intent);

            onModeLoaded(Mode.MOVIE, videoView);
        } else {
            exoVideoView = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.clover_player_view, this, false);
            exoPlayer = new ExoPlayer.Builder(getContext())
                    .setSeekBackIncrementMs(5000)
                    .setSeekForwardIncrementMs(15000)
                    .build();
            exoVideoView.setPlayer(exoPlayer);
            exoVideoView.setControllerShowTimeoutMs(ChanSettings.videoExoPlayerTimeout.get() * 1000);

            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(getContext());
            ProgressiveMediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(file)));

            exoPlayer.setRepeatMode(ChanSettings.videoAutoLoop.get() ?
                    Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);

            exoPlayer.setMediaSource(videoSource);
            exoPlayer.prepare();
            exoPlayer.addListener(this);
            exoPlayer.setVolume(0f);

            playView.setVisibility(View.GONE);

            addView(exoVideoView);
            exoPlayer.setPlayWhenReady(true);
            onModeLoaded(Mode.MOVIE, exoVideoView);
            callback.onVideoLoaded(this);
        }
    }

    private boolean hasMediaPlayerAudioTracks(MediaPlayer mediaPlayer) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                for (MediaPlayer.TrackInfo trackInfo : mediaPlayer.getTrackInfo()) {
                    if (trackInfo.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        return true;
                    }
                }

                return false;
            } else {
                // It'll just show the icon without doing anything. Remove when 4.0 is dropped.
                return true;
            }
        } catch (RuntimeException e) {
            // getTrackInfo() raises an IllegalStateException on some devices.
            // Samsung even throws a RuntimeException.
            // Return a default value.
            return true;
        }
    }

    private boolean hasMediaPlayerAudioTracks(ExoPlayer mediaPlayer) {
        for (Tracks.Group group : mediaPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
    }

    private void onVideoError() {
        if (!videoError) {
            videoError = true;
            callback.onVideoError(this);
        }
    }

    private void cleanupVideo(VideoView videoView) {
        videoView.stopPlayback();
        mediaPlayer = null;
    }

    private void cleanupVideo(PlayerView videoView) {
        Player player = videoView.getPlayer();
        if (player != null) {
            player.removeListener(this);
            player.release();
        }
        if (videoView.getPlayer() == exoPlayer) {
            exoPlayer = null;
        }
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (exoPlayer != null) {
            boolean hasAudio = false;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                    break;
                }
            }

            if (hasAudio) {
                callback.onAudioLoaded(this);
            }
        }
    }

    public void toggleTransparency() {
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        if (imageView == null && gifView == null) return;
        boolean isImage = imageView != null && gifView == null;
        int backgroundColor = backgroundToggle ? Color.TRANSPARENT : BACKGROUND_COLOR;
        if (isImage) {
            imageView.setTileBackgroundColor(backgroundColor);
        } else {
            gifView.getDrawable().setColorFilter(backgroundColor, PorterDuff.Mode.DST_OVER);
        }
        backgroundToggle = !backgroundToggle;
    }

    public void setOrientation(int orientation) {
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        if (imageView == null && gifView == null) return;
        boolean isImage = imageView != null && gifView == null;
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
        } else if (gifView != null) {
            if (orientation < 0) {
                if (orientation == -1) {
                    gifView.setScaleX(-1f);
                    gifView.setScaleY(1f);
                } else {
                    gifView.setScaleX(1f);
                    gifView.setScaleY(-1f);
                }
            } else {
                gifView.setScaleX(1f);
                gifView.setScaleY(1f);
                if (orientation == 0) {
                    gifView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                } else {
                    gifView.setScaleType(ImageView.ScaleType.MATRIX);
                    int iw = gifView.getDrawable().getIntrinsicWidth();
                    int ih = gifView.getDrawable().getIntrinsicHeight();
                    RectF dstRect = new RectF(0, 0, gifView.getWidth(), gifView.getHeight());
                    Matrix matrix = new Matrix();
                    if (orientation == 90 || orientation == 270) {
                        matrix.setRectToRect(new RectF(0, 0, ih, iw), dstRect, Matrix.ScaleToFit.CENTER);
                        matrix.preRotate(90f, ih / 2, ih / 2);
                    } else {
                        matrix.setRectToRect(new RectF(0, 0, iw, ih), dstRect, Matrix.ScaleToFit.CENTER);
                    }
                    if (orientation >= 180)
                        matrix.preRotate(180f, iw / 2, ih / 2);
                    gifView.setImageMatrix(matrix);
                }
            }
        }
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
        String message = getContext().getString(R.string.image_preview_failed);
        String extra = e.getMessage() == null ? "" : ": " + e.getMessage();
        Toast.makeText(getContext(), message + extra, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onNotFoundError() {
        callback.showProgress(this, false);
        Toast.makeText(getContext(), R.string.image_not_found, Toast.LENGTH_SHORT).show();
    }

    private void onOutOfMemoryError() {
        Toast.makeText(getContext(), R.string.image_preview_failed_oom, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onBigImageError(boolean wasInitial) {
        if (wasInitial) {
            Toast.makeText(getContext(), R.string.image_failed_big_image, Toast.LENGTH_SHORT).show();
            callback.showProgress(this, false);
        }
    }

    private void cancelLoad() {
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
        if (exoPlayer != null) {
            // ExoPlayer will keep loading resources if we don't release it here.
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    private void onModeLoaded(Mode mode, View view) {
        if (view != null) {
            // Remove all other views
            boolean alreadyAttached = false;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child != playView) {
                    if (child != view) {
                        if (child instanceof VideoView) {
                            cleanupVideo((VideoView) child);
                        } else if (child instanceof PlayerView) {
                            cleanupVideo((PlayerView) child);
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
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child instanceof GifImageView) {
            GifImageView gif = (GifImageView) child;
            if (gif.getDrawable() instanceof GifDrawable) {
                GifDrawable drawable = (GifDrawable) gif.getDrawable();
                if (drawable.getFrameByteCount() > 100 * 1024 * 1024) { //max size from RecordingCanvas
                    onError(new Exception("Uncompressed GIF too large (>100MB)"));
                    return false;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public interface Callback {
        void onTap(MultiImageView multiImageView);

        void showProgress(MultiImageView multiImageView, boolean progress);

        void onProgress(MultiImageView multiImageView, long current, long total);

        void onVideoError(MultiImageView multiImageView);

        void onVideoLoaded(MultiImageView multiImageView);

        void onModeLoaded(MultiImageView multiImageView, Mode mode);

        void onAudioLoaded(MultiImageView multiImageView);
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
