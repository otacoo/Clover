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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
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
import androidx.media3.ui.PlayerView;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
    private boolean vp9FallbackAttempted = false;
    private ExoPlayer exoPlayer;

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
                                byte[] data = body.bytes();
                                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
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
        vp9FallbackAttempted = false;
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(root, 0, lp);

        // This empty mimeType list makes MediaCodecVideoRenderer report FORMAT_UNSUPPORTED_SUBTYPE, so 
        // that track selection falls through to the LibvpxVideoRenderer added by EXTENSION_RENDERER_MODE_ON.
        MediaCodecSelector vp9ToLibvpxSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            if (MimeTypes.VIDEO_VP9.equals(mimeType)) {
                return Collections.emptyList();
            }
            return MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        };

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setMediaCodecSelector(vp9ToLibvpxSelector)
                .setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        30000,
                        90000,
                        2500,
                        5000
                )
                .build();

        exoPlayer = new ExoPlayer.Builder(new NoMusicServiceCommandContext(getContext()), renderersFactory)
                .setLoadControl(loadControl)
                .build();

        exoPlayerView.setPlayer(exoPlayer);

        MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(file));
        exoPlayer.setMediaItem(mediaItem);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    AndroidUtils.runOnUiThread(() -> {
                        if (isAttachedToWindow() && exoPlayer != null) {
                            onModeLoaded(Mode.MOVIE, root);
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
                            playerPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_circle_filled_white : R.drawable.ic_play_circle_filled_white);
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
                // If libvpx rejected the bitstream (e.g. 10-bit / Profile 2),
                // retry once using the Android software Codec2 VP9 decoder
                if (!vp9FallbackAttempted
                        && (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                            || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) {
                    vp9FallbackAttempted = true;
                    handler.removeCallbacks(updateTimeTask);
                    handler.removeCallbacks(hideControllerTask);
                    if (exoPlayer != null) {
                        exoPlayer.release();
                        exoPlayer = null;
                    }
                    startSoftwareVp9Player(file);
                    return;
                }
                onVideoError();
            }
        });

        isMuted = ChanSettings.videoDefaultMuted.get();
        exoPlayer.setVolume(isMuted ? 0f : 1f);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();

        callback.onVideoLoaded(this);
    }

    // Retries VP9 playback using c2.android.vp9.decoder (AOSP software Codec2).
    // This decoder should support all VP9 profiles including 10-bit
    // Called once on decoder failure.
    private void startSoftwareVp9Player(File file) {
        MediaCodecSelector softwareVp9Selector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            if (MimeTypes.VIDEO_VP9.equals(mimeType)) {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                List<MediaCodecInfo> filtered = new ArrayList<>();
                for (MediaCodecInfo info : all) {
                       // This excludes hardware OMX decoders (OMX.*) which may not support 10-bit.
                    if (info.name.startsWith("c2.android.")) {
                        filtered.add(info);
                    }
                }
                return filtered;
            }
            return MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        };

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setMediaCodecSelector(softwareVp9Selector)
                .setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        500,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();

        exoPlayer = new ExoPlayer.Builder(new NoMusicServiceCommandContext(getContext()), renderersFactory)
                .setLoadControl(loadControl)
                .build();

        if (exoPlayerView != null) {
            exoPlayerView.setPlayer(exoPlayer);
        }

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
                onVideoError();
            }
        });

        exoPlayer.setVolume(isMuted ? 0f : 1f);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                gifView.getDrawable().setColorFilter(new BlendModeColorFilter(backgroundColor, BlendMode.DST_OVER));
            } else {
                android.graphics.ColorFilter legacyFilter = new android.graphics.PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.DST_OVER);
                gifView.getDrawable().setColorFilter(legacyFilter);
            }
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
                        matrix.preRotate(90f, (float) ih / 2, (float) ih / 2);
                    } else {
                        matrix.setRectToRect(new RectF(0, 0, iw, ih), dstRect, Matrix.ScaleToFit.CENTER);
                    }
                    if (orientation >= 180)
                        matrix.preRotate(180f, (float) iw / 2, (float) ih / 2);
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
        if (!isAttachedToWindow()) return;
        String message = getContext().getString(R.string.image_preview_failed);
        String extra = e.getMessage() == null ? "" : ": " + e.getMessage();
        Toast.makeText(getContext(), message + extra, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onNotFoundError() {
        if (!isAttachedToWindow()) return;
        callback.showProgress(this, false);
        Toast.makeText(getContext(), R.string.image_not_found, Toast.LENGTH_SHORT).show();
    }

    private void onOutOfMemoryError() {
        if (!isAttachedToWindow()) return;
        Toast.makeText(getContext(), R.string.image_preview_failed_oom, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onBigImageError(boolean wasInitial) {
        if (!isAttachedToWindow()) return;
        if (wasInitial) {
            Toast.makeText(getContext(), R.string.image_failed_big_image, Toast.LENGTH_SHORT).show();
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
