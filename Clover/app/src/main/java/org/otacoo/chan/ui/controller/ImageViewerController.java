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
package org.otacoo.chan.ui.controller;

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getDimen;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.davemorrissey.labs.subscaleview.ImageViewState;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.presenter.ImageViewerPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.ImageSearch;
import org.otacoo.chan.ui.adapter.ImageViewerAdapter;
import org.otacoo.chan.ui.toolbar.NavigationItem;
import org.otacoo.chan.ui.toolbar.Toolbar;
import org.otacoo.chan.ui.toolbar.ToolbarMenu;
import org.otacoo.chan.ui.toolbar.ToolbarMenuItem;
import org.otacoo.chan.ui.toolbar.ToolbarMenuSubItem;
import org.otacoo.chan.ui.view.CustomScaleImageView;
import org.otacoo.chan.ui.view.FloatingMenu;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.LoadingBar;
import org.otacoo.chan.ui.view.MultiImageView;
import org.otacoo.chan.ui.view.OptionalSwipeViewPager;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.ui.view.TransitionImageView;
import org.otacoo.chan.utils.AndroidUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@OptIn(markerClass = UnstableApi.class)
public class ImageViewerController extends Controller implements ImageViewerPresenter.Callback {
    private static final String TAG = "ImageViewerController";
    private static final int TRANSITION_DURATION = 200;
    private static final int TRANSITION_OUT_DURATION = 150;
    private static final float TRANSITION_FINAL_ALPHA = 0.92f;

    private static final int VOLUME_ID = 1;

    @Inject
    OkHttpClient okHttpClient;

    private int statusBarColorPrevious;
    private AnimatorSet startAnimation;
    private AnimatorSet endAnimation;

    private ImageViewerCallback imageViewerCallback;
    private GoPostCallback goPostCallback;
    private final ImageViewerPresenter presenter;

    private final Toolbar toolbar;
    private TransitionImageView previewImage;
    private OptionalSwipeViewPager pager;
    private LoadingBar loadingBar;

    private static final int SUBITEM_TRANSPARENCY_TOGGLE_ID = 220;
    private static final int SUBITEM_ROTATE_IMAGE_ID = 221;
    private ToolbarMenu toolbarMenu;

    private boolean isInImmersiveMode = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Call inTransitionCall;

    @SuppressWarnings("this-escape")
    public ImageViewerController(Context context, Toolbar toolbar) {
        super(context);
        inject(this);

        this.toolbar = toolbar;

        presenter = new ImageViewerPresenter(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Navigation
        navigation.subtitle = "0";
        navigation.handlesToolbarInset = true;

        NavigationItem.MenuBuilder menuBuilder = navigation.buildMenu();
        if (goPostCallback != null) {
            menuBuilder.withItem(R.drawable.ic_subdirectory_arrow_left_white_24dp, this::goPostClicked);
        }

        menuBuilder.withItem(VOLUME_ID, R.drawable.ic_volume_off_white_24dp, this::volumeClicked);
        menuBuilder.withItem(R.drawable.ic_file_download_white_24dp, this::saveClicked);

        NavigationItem.MenuOverflowBuilder overflowBuilder = menuBuilder.withOverflow();
        overflowBuilder.withSubItem(R.string.action_open_browser, this::openBrowserClicked);
        overflowBuilder.withSubItem(R.string.action_copy_url, this::clipboardURL);
        overflowBuilder.withSubItem(R.string.action_share, this::shareClicked);
        overflowBuilder.withSubItem(R.string.action_search_image, this::searchClicked);
        overflowBuilder.withSubItem(R.string.action_download_album, this::downloadAlbumClicked);
        overflowBuilder.withSubItem(SUBITEM_TRANSPARENCY_TOGGLE_ID, R.string.action_transparency_toggle, this::toggleTransparency);
        overflowBuilder.withSubItem(SUBITEM_ROTATE_IMAGE_ID, R.string.action_rotate_image, this::setOrientation);

        toolbarMenu = overflowBuilder.build().build();

        hideSystemUI();

        // View setup
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        view = inflateRes(R.layout.controller_image_viewer);
        previewImage = view.findViewById(R.id.preview_image);
        pager = view.findViewById(R.id.pager);
        pager.addOnPageChangeListener(presenter);
        loadingBar = view.findViewById(R.id.loading_bar);

        showVolumeMenuItem(false, true);

        // Sanity check
        if (parentController.view.getWindowToken() == null) {
            throw new IllegalArgumentException("parentController.view not attached");
        }

        AndroidUtils.waitForLayout(parentController.view.getViewTreeObserver(), view, view -> {
            presenter.onViewMeasured();
            return true;
        });

        updateViewMargins();
    }

    private void updateViewMargins() {
        if (view == null) return;
        boolean immersive = ChanSettings.useImmersiveModeForGallery.get();
        int topMargin = immersive ? 0 : toolbar.getToolbarHeight();
        
        setTopMargin(previewImage, topMargin);
        setTopMargin(pager, topMargin);
        setTopMargin(loadingBar, topMargin);
    }

    private void setTopMargin(View v, int margin) {
        if (v == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (params.topMargin != margin) {
            params.topMargin = margin;
            v.setLayoutParams(params);
        }
    }

    private void goPostClicked(ToolbarMenuItem item) {
        PostImage postImage = presenter.getCurrentPostImage();
        ImageViewerCallback callback = goPostCallback.goToPost(postImage);
        if (callback != null) {
            // hax: we need to wait for the recyclerview to do a layout before we know
            // where the new thumbnails are to get the bounds from to animate to
            this.imageViewerCallback = callback;
            AndroidUtils.waitForLayout(view, view -> {
                showSystemUI();
                handler.removeCallbacksAndMessages(null);
                presenter.onExit();
                return false;
            });
        } else {
            showSystemUI();
            handler.removeCallbacksAndMessages(null);
            presenter.onExit();
        }
    }

    private void volumeClicked(ToolbarMenuItem item) {
        presenter.onVolumeClicked();
    }

    private void saveClicked(ToolbarMenuItem item) {
        presenter.saveClicked(presenter.getCurrentPostImage());
    }

    private void openBrowserClicked(ToolbarMenuSubItem item) {
        PostImage postImage = presenter.getCurrentPostImage();
        if (ChanSettings.openLinkBrowser.get()) {
            AndroidUtils.openLink(postImage.imageUrl.toString());
        } else {
            Activity activity = getActivity();
            if (activity != null) {
                AndroidUtils.openLinkInBrowser(activity, postImage.imageUrl.toString());
            }
        }
    }

    private void clipboardURL(ToolbarMenuSubItem item) {
        PostImage postImage = presenter.getCurrentPostImage();
        ClipboardManager clipboard = (ClipboardManager) AndroidUtils.getAppContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("File URL", postImage.imageUrl.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, R.string.url_text_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareClicked(ToolbarMenuSubItem item) {
        presenter.shareClicked(presenter.getCurrentPostImage());
    }

    private void searchClicked(ToolbarMenuSubItem item) {
        showImageSearchOptions(presenter.getLoadable().board.code);
    }

    private void downloadAlbumClicked(ToolbarMenuSubItem item) {
        List<PostImage> all = presenter.getAllPostImages();
        AlbumDownloadController albumDownloadController = new AlbumDownloadController(context);
        albumDownloadController.setPostImages(presenter.getLoadable(), all);
        navigationController.pushController(albumDownloadController);
    }

    private void toggleTransparency(ToolbarMenuSubItem item) {
        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        if (adapter != null) {
            adapter.toggleTransparency(presenter.getCurrentPostImage());
        }
    }

    private void setOrientation(ToolbarMenuSubItem item) {
        getOrientationMenu().show();
    }

    @NonNull
    private FloatingMenu getOrientationMenu() {
        List<FloatingMenuItem> orientations = Arrays.asList(
                new FloatingMenuItem(0, "Reset view"),
                new FloatingMenuItem(90, "Rotate 90 deg."),
                new FloatingMenuItem(180, "Rotate 180 deg."),
                new FloatingMenuItem(270, "Rotate 270 deg."),
                new FloatingMenuItem(-1, "Flip horizontally"),
                new FloatingMenuItem(-2, "Flip vertically")
        );
        FloatingMenu menu = new FloatingMenu(context, view, orientations);
        menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
            @Override
            public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
                if (adapter != null) {
                    adapter.setOrientation(presenter.getCurrentPostImage(), (int) item.getId());
                }
            }

            @Override
            public void onFloatingMenuDismissed(FloatingMenu menu) { }
        });
        return menu;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        showSystemUI();
        handler.removeCallbacksAndMessages(null);
        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (inTransitionCall != null) {
            inTransitionCall.cancel();
        }
    }

    @Override
    public boolean onBack() {
        if (presenter.isTransitioning()) return false;
        showSystemUI();
        handler.removeCallbacksAndMessages(null);
        presenter.onExit();
        return true;
    }

    public void setImageViewerCallback(ImageViewerCallback imageViewerCallback) {
        this.imageViewerCallback = imageViewerCallback;
    }

    public void setGoPostCallback(GoPostCallback goPostCallback) {
        this.goPostCallback = goPostCallback;
    }

    public ImageViewerPresenter getPresenter() {
        return presenter;
    }

    public void setPreviewVisibility(boolean visible) {
        previewImage.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    public void setPagerVisiblity(boolean visible) {
        pager.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        pager.setSwipingEnabled(visible);
    }

    public void setPagerItems(List<PostImage> images, int initialIndex) {
        ImageViewerAdapter adapter = new ImageViewerAdapter(context, images, presenter);
        pager.setAdapter(adapter);
        pager.setCurrentItem(initialIndex);
    }

    public void setImageMode(PostImage postImage, MultiImageView.Mode mode, boolean center) {
        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        if (adapter != null) {
            adapter.setMode(postImage, mode, center);
        }
    }

    @Override
    public void setVolume(PostImage postImage, boolean muted) {
        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        if (adapter != null) {
            adapter.setVolume(postImage, muted);
        }
    }

    public MultiImageView.Mode getImageMode(PostImage postImage) {
        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        return adapter != null ? adapter.getMode(postImage) : null;
    }

    public void setTitle(PostImage postImage, int index, int count, boolean spoiler) {
        if (spoiler) {
            navigation.title = getString(R.string.image_spoiler_filename);
        } else {
            navigation.title = postImage.filename + "." + postImage.extension;
        }
        navigation.subtitle = (index + 1) + "/" + count;
        ((ToolbarNavigationController) navigationController).toolbar.updateTitle(navigation);

        if (toolbarMenu == null) return;
        MultiImageView.Mode imageMode = getImageMode(postImage);
        boolean enabled = !spoiler && (imageMode == MultiImageView.Mode.BIGIMAGE || imageMode == MultiImageView.Mode.GIF);
        toolbarMenu.findSubItem(SUBITEM_TRANSPARENCY_TOGGLE_ID).enabled = enabled;
        toolbarMenu.findSubItem(SUBITEM_ROTATE_IMAGE_ID).enabled = enabled;
    }

    public void scrollToImage(PostImage postImage) {
        imageViewerCallback.scrollToImage(postImage);
    }

    public void showProgress(boolean show) {
        loadingBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void onLoadProgress(float progress) {
        loadingBar.setProgress(progress);
    }

    public void onVideoError(MultiImageView multiImageView) {
        if (ChanSettings.videoErrorIgnore.get()) {
            Toast.makeText(context, R.string.video_playback_failed, Toast.LENGTH_SHORT).show();
        } else {
            @SuppressLint("InflateParams")
            View notice = LayoutInflater.from(context).inflate(R.layout.dialog_video_error, null);
            final CheckBox dontShowAgain = notice.findViewById(R.id.checkbox);

            new AlertDialog.Builder(context)
                    .setTitle(R.string.video_playback_warning_title)
                    .setView(notice)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        if (dontShowAgain.isChecked()) {
                            ChanSettings.videoErrorIgnore.set(true);
                        }
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    @Override
    public void showVolumeMenuItem(boolean show, boolean muted) {
        ToolbarMenuItem volumeMenuItem = navigation.findItem(VOLUME_ID);
        volumeMenuItem.setVisible(show);
        volumeMenuItem.setImage(
                muted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
    }

    private void showImageSearchOptions(String boardCode) {
        ToolbarMenuItem overflowMenuItem = navigation.findItem(ToolbarMenu.OVERFLOW_ID);
        createSearchEngineMenu(boardCode, overflowMenuItem.getView()).show();
    }

    private FloatingMenu createSearchEngineMenu(String boardCode, View anchor) {
        List<FloatingMenuItem> items = new ArrayList<>();
        for (ImageSearch imageSearch : ImageSearch.engines) {
            if (imageSearch.showFor(boardCode)) {
                items.add(new FloatingMenuItem(imageSearch.getId(), imageSearch.getName()));
            }
        }
        FloatingMenu menu = new FloatingMenu(context, anchor, items);
        menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
            @Override
            public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                for (ImageSearch imageSearch : ImageSearch.engines) {
                    if (Objects.equals(item.getId(), imageSearch.getId())) {
                        final HttpUrl searchImageUrl = getSearchImageUrl(presenter.getCurrentPostImage());
                        Activity activity = getActivity();
                        if (activity != null) {
                            AndroidUtils.openLinkInBrowser(activity, imageSearch.getUrl(searchImageUrl.toString()));
                        }
                        break;
                    }
                }
            }

            @Override
            public void onFloatingMenuDismissed(FloatingMenu menu) {
            }
        });
        return menu;
    }

    @Override
    public boolean isImmersive() {
        return ChanSettings.useImmersiveModeForGallery.get() && isInImmersiveMode;
    }

    @SuppressWarnings("deprecation")
    public void startPreviewInTransition(PostImage postImage) {
        ThumbnailView startImageView = getTransitionImageView(postImage);

        if (trySetupTransitionView(startImageView)) {
            Window window = getWindow();
            if (window != null) {
                statusBarColorPrevious = window.getStatusBarColor();
            }

            setBackgroundAlpha(0f);

            startAnimation = new AnimatorSet();

            ValueAnimator progress = ValueAnimator.ofFloat(0f, 1f);
            progress.addUpdateListener(animation -> {
                setBackgroundAlpha(Math.min(1f, (float) animation.getAnimatedValue()));
                previewImage.setProgress((float) animation.getAnimatedValue());
            });

            startAnimation.play(progress);
            startAnimation.setDuration(TRANSITION_DURATION);
            startAnimation.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            startAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    imageViewerCallback.onPreviewCreate(ImageViewerController.this, postImage);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    startAnimation = null;
                    presenter.onInTransitionEnd();
                }
            });

            Bitmap cached = ThumbnailView.getCachedBitmap(postImage.getThumbnailUrl().toString());
            if (cached != null) {
                previewImage.setBitmap(cached);
                startAnimation.start();
                return;
            }

            Request request = new Request.Builder().url(postImage.getThumbnailUrl().toString()).build();
            inTransitionCall = okHttpClient.newCall(request);
            inTransitionCall.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    inTransitionCall = null;
                    Log.e(TAG, "onFailure for preview in transition", e);
                    AndroidUtils.runOnUiThread(() -> {
                        if (alive && startAnimation != null) {
                            startAnimation.start();
                        }
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful()) {
                            try (ResponseBody body = response.body()) {
                                if (body != null) {
                                    byte[] data = body.bytes();
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                                    if (bitmap != null) {
                                        AndroidUtils.runOnUiThread(() -> {
                                            if (alive && startAnimation != null) {
                                                previewImage.setBitmap(bitmap);
                                                startAnimation.start();
                                            }
                                        });
                                        return;
                                    }
                                }
                            }
                        }
                        AndroidUtils.runOnUiThread(() -> {
                            if (alive && startAnimation != null) {
                                startAnimation.start();
                            }
                        });
                    } finally {
                        inTransitionCall = null;
                    }
                }
            });
        }
    }

    public void startPreviewOutTransition(final PostImage postImage) {
        if (startAnimation != null || endAnimation != null) {
            return;
        }

        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        if (adapter != null) {
            adapter.pauseAll();
        }

        Bitmap cached = ThumbnailView.getCachedBitmap(postImage.getThumbnailUrl().toString());
        doPreviewOutAnimation(postImage, cached);
    }

    private void doPreviewOutAnimation(PostImage postImage, Bitmap bitmap) {
        setPagerVisiblity(false);
        setPreviewVisibility(true);

        previewImage.setState(1f, null, null);
        previewImage.setAlpha(1f);
        previewImage.setTranslationY(0);

        // Find translation and scale if the current displayed image was a bigimage
        ImageViewerAdapter adapter = (ImageViewerAdapter) pager.getAdapter();
        if (adapter != null) {
            MultiImageView multiImageView = adapter.find(postImage);
            if (multiImageView != null) {
                CustomScaleImageView customScaleImageView = multiImageView.findScaleImageView();
                if (customScaleImageView != null) {
                    ImageViewState state = customScaleImageView.getState();
                    if (state != null) {
                        PointF p = customScaleImageView.viewToSourceCoord(0f, 0f);
                        PointF bitmapSize = new PointF(customScaleImageView.getSWidth(), customScaleImageView.getSHeight());
                        previewImage.setState(state.getScale(), p, bitmapSize);
                    }
                }
            }
        }

        ThumbnailView startImage = getTransitionImageView(postImage);

        endAnimation = new AnimatorSet();
        if (bitmap != null && trySetupTransitionView(startImage)) {
            previewImage.setBitmap(bitmap);
            previewImage.setProgress(1f);

            ValueAnimator progress = ValueAnimator.ofFloat(1f, 0f);
            progress.addUpdateListener(animation -> {
                setBackgroundAlpha((float) animation.getAnimatedValue());
                previewImage.setProgress((float) animation.getAnimatedValue());
            });

            endAnimation.play(progress);
        } else {
            if (bitmap != null) {
                previewImage.setBitmap(bitmap);
            }

            previewImage.setProgress(1f);

            ValueAnimator backgroundAlpha = ValueAnimator.ofFloat(1f, 0f);
            backgroundAlpha.addUpdateListener(animation -> setBackgroundAlpha((float) animation.getAnimatedValue()));

            endAnimation
                    .play(ObjectAnimator.ofFloat(previewImage, View.ALPHA, 1f, 0f))
                    .with(ObjectAnimator.ofFloat(previewImage, View.TRANSLATION_Y, 0, dp(20)))
                    .with(backgroundAlpha);
        }

        endAnimation.setDuration(TRANSITION_OUT_DURATION);
        endAnimation.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        endAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                endAnimation = null;
                previewOutAnimationEnded(postImage);
            }
        });

        imageViewerCallback.onBeforePreviewDestroy(this, postImage);
        endAnimation.start();
    }

    private void previewOutAnimationEnded(PostImage postImage) {
        setBackgroundAlpha(0f);

        imageViewerCallback.onPreviewDestroy(this, postImage);
        navigationController.stopPresenting(false);
    }

    private boolean trySetupTransitionView(ThumbnailView startView) {
        if (startView == null || startView.getWindowToken() == null) {
            return false;
        }

        Bitmap bitmap = startView.getBitmap();
        if (bitmap == null) {
            return false;
        }

        int[] loc = new int[2];
        startView.getLocationInWindow(loc);
        Point windowLocation = new Point(loc[0], loc[1]);
        Point size = new Point(startView.getWidth(), startView.getHeight());
        previewImage.setSourceImageView(windowLocation, size, bitmap, startView.getRounding());
        return true;
    }

    private void setBackgroundAlpha(float alpha) {
        float overlayFinalAlpha = (ChanSettings.useImmersiveModeForGallery.get() && isInImmersiveMode)
            ? 1f : TRANSITION_FINAL_ALPHA;
        navigationController.view.setBackgroundColor(Color.argb((int) (alpha * overlayFinalAlpha * 255f), 0, 0, 0));

        if (alpha == 0f) {
            setStatusBarColor(statusBarColorPrevious);
        } else {
            int r = (int) ((1f - alpha) * Color.red(statusBarColorPrevious));
            int g = (int) ((1f - alpha) * Color.green(statusBarColorPrevious));
            int b = (int) ((1f - alpha) * Color.blue(statusBarColorPrevious));
            setStatusBarColor(Color.argb(255, r, g, b));
        }

        setToolbarBackgroundAlpha(alpha);
    }

    @SuppressWarnings("deprecation")
    private void setStatusBarColor(int color) {
        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(color);
        }
    }

    private void setToolbarBackgroundAlpha(float alpha) {
        toolbar.setAlpha(alpha);
        loadingBar.setAlpha(alpha);
    }

    private ThumbnailView getTransitionImageView(PostImage postImage) {
        return imageViewerCallback.getPreviewImageTransitionView(this, postImage);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        if (!ChanSettings.useImmersiveModeForGallery.get() || isInImmersiveMode) {
            return;
        }

        isInImmersiveMode = true;

        Window window = getWindow();
        if (window != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decorView = window.getDecorView();
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

                decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 && isInImmersiveMode) {
                        showSystemUI();
                        handler.postDelayed(this::hideSystemUI, 2500);
                    }
                });
            }
        }

        ViewGroup.LayoutParams params = navigationController.getToolbar().getLayoutParams();
        params.height = 0;
        navigationController.getToolbar().setLayoutParams(params);
        navigationController.getToolbar().bringToFront();

        updateViewMargins();
    }

    @Override
    public void showSystemUI(boolean show) {
        if (show) {
            showSystemUI();
            handler.postDelayed(this::hideSystemUI, 2500);
        } else {
            hideSystemUI();
        }
    }

    @SuppressWarnings("deprecation")
    private void showSystemUI() {
        if (!ChanSettings.useImmersiveModeForGallery.get() || !isInImmersiveMode) {
            return;
        }

        isInImmersiveMode = false;

        Window window = getWindow();
        if (window != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                View decorView = window.getDecorView();
                decorView.setOnSystemUiVisibilityChangeListener(null);
                decorView.setSystemUiVisibility(0);
            }
        }

        ViewGroup.LayoutParams params = navigationController.getToolbar().getLayoutParams();
        params.height = getDimen(context, R.dimen.toolbar_height);
        navigationController.getToolbar().setLayoutParams(params);

        updateViewMargins();
    }

    private Window getWindow() {
        Activity activity = getActivity();
        return activity != null ? activity.getWindow() : null;
    }

    private Activity getActivity() {
        Context context = this.context;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public interface ImageViewerCallback {
        ThumbnailView getPreviewImageTransitionView(ImageViewerController imageViewerController, PostImage postImage);

        void onPreviewCreate(ImageViewerController imageViewerController, PostImage postImage);

        void onBeforePreviewDestroy(ImageViewerController imageViewerController, PostImage postImage);

        void onPreviewDestroy(ImageViewerController imageViewerController, PostImage postImage);

        void scrollToImage(PostImage postImage);
    }

    public interface GoPostCallback {
        ImageViewerCallback goToPost(PostImage postImage);
    }

    private HttpUrl getSearchImageUrl(final PostImage postImage) {
        return postImage.type == PostImage.Type.MOVIE ? postImage.thumbnailUrl : postImage.imageUrl;
    }
}
