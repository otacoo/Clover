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
package org.otacoo.chan.ui.layout;

import static org.otacoo.chan.utils.AndroidUtils.ROBOTO_MEDIUM;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.getDimen;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.ChanThread;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.presenter.ReplyPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.core.site.sites.chan4.Chan4;
import org.otacoo.chan.ui.adapter.PostAdapter;
import org.otacoo.chan.ui.adapter.PostsFilter;
import org.otacoo.chan.ui.cell.PostCell;
import org.otacoo.chan.ui.cell.PostCellInterface;
import org.otacoo.chan.ui.cell.PostStubCell;
import org.otacoo.chan.ui.cell.ThreadStatusCell;
import org.otacoo.chan.ui.toolbar.Toolbar;
import org.otacoo.chan.ui.view.FastScroller;
import org.otacoo.chan.ui.view.FastScrollerHelper;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;

/**
 * A layout that wraps around a {@link RecyclerView} and a {@link ReplyLayout} to manage showing and replying to posts.
 */
public class ThreadListLayout extends FrameLayout implements ReplyLayout.ReplyLayoutCallback {
    public static final int MAX_SMOOTH_SCROLL_DISTANCE = 20;
    private static final long SCROLL_SAVE_DELAY = 250;

    private ReplyLayout reply;
    private TextView searchStatus;
    private RecyclerView recyclerView;
    private RecyclerView.LayoutManager layoutManager;
    private FastScroller fastScroller;
    private PostAdapter postAdapter;
    private ChanThread showingThread;
    private ThreadListLayoutPresenterCallback callback;
    private ThreadListLayoutCallback threadListLayoutCallback;
    private boolean replyOpen;
    private ChanSettings.PostViewMode postViewMode;
    private int spanCount = 2;
    private int background;
    private boolean searchOpen;
    private int lastPostCount;
    private int threadLastViewed = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable saveScrollPositionRunnable = new Runnable() {
        @Override
        public void run() {
            if (showingThread != null) {
                int[] indexTop = getIndexAndTop();
                showingThread.loadable.setListViewIndex(indexTop[0]);
                showingThread.loadable.setListViewTop(indexTop[1]);

                // If already at bottom, onListScrolledToBottom() already handled counts
                if (scrolledToBottom()) return;

                int lastVisible = getBottomAdapterPosition();
                if (lastVisible >= 0 && postAdapter != null) {
                    int postPos = postAdapter.getPostPosition(lastVisible);
                    if (postPos < 0) {
                        postPos = postAdapter.getDisplayList().size() - 1;
                    }
                    if (postPos >= 0 && postPos < postAdapter.getDisplayList().size()) {
                        Post post = postAdapter.getDisplayList().get(postPos);
                        callback.onPostSeen(post.no);
                    }
                }
            }
        }
    };

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            onRecyclerViewScrolled(dy);
        }
    };

    public ThreadListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // View binding
        reply = findViewById(R.id.reply);
        searchStatus = findViewById(R.id.search_status);
        recyclerView = findViewById(R.id.recycler_view);

        // View setup
        reply.setCallback(this);
        searchStatus.setTypeface(ROBOTO_MEDIUM);
    }

    public void setCallbacks(PostAdapter.PostAdapterCallback postAdapterCallback,
                             PostCell.PostCellCallback postCellCallback,
                             ThreadStatusCell.Callback statusCellCallback,
                             ThreadListLayoutPresenterCallback callback,
                             ThreadListLayoutCallback threadListLayoutCallback) {
        this.callback = callback;
        this.threadListLayoutCallback = threadListLayoutCallback;

        postAdapter = new PostAdapter(recyclerView, postAdapterCallback, postCellCallback, statusCellCallback);
        recyclerView.setAdapter(postAdapter);
        recyclerView.addOnScrollListener(scrollListener);
        reply.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (replyOpen && bottom - top != oldBottom - oldTop) {
                applyReplyLayoutMode();
                setRecyclerViewPadding();
                threadListLayoutCallback.replyLayoutChanged();
            }
        });

        applyReplyLayoutMode();

        setFastScroll(false);

        attachToolbarScroll(true);

        if (!ChanSettings.toolbarBottom.get()) {
            searchStatus.setPadding(searchStatus.getPaddingLeft(), searchStatus.getPaddingTop() + toolbarHeight(),
                    searchStatus.getPaddingRight(), searchStatus.getPaddingBottom());
        } else {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) searchStatus.getLayoutParams();
            params.gravity = Gravity.BOTTOM;
            searchStatus.setLayoutParams(params);

            searchStatus.setPadding(searchStatus.getPaddingLeft(), searchStatus.getPaddingTop(),
                    searchStatus.getPaddingRight(), searchStatus.getPaddingBottom() + toolbarHeight());
        }
    }

    private void onRecyclerViewScrolled(int dy) {
        // onScrolled can be called after cleanup()
        if (showingThread != null) {
            mainHandler.removeCallbacks(saveScrollPositionRunnable);
            mainHandler.postDelayed(saveScrollPositionRunnable, SCROLL_SAVE_DELAY);

            if (scrolledToBottom() && !postAdapter.getDisplayList().isEmpty()) {
                int bottom = postAdapter.getDisplayList().size() - 1;
                if (bottom > lastPostCount) {
                    lastPostCount = bottom;
                    Post lastPost = postAdapter.getDisplayList().get(bottom);
                    callback.onListScrolledToBottom();
                    recyclerView.post(() -> postAdapter.clearLastSeenIndicator());
                    threadLastViewed = lastPost.no;
                }
            }

            threadListLayoutCallback.onScrolling(dy);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            updateSpanCount(w);
        }
    }

    private void updateSpanCount(int width) {
        int cardWidth = getResources().getDimensionPixelSize(R.dimen.grid_card_width);
        int gridCountSetting = ChanSettings.boardGridSpanCount.get();
        boolean compactMode;
        if (gridCountSetting > 0) {
            spanCount = gridCountSetting;
            compactMode = (width / spanCount) < dp(120);
        } else {
            spanCount = Math.max(1, Math.round((float) width / cardWidth));
            compactMode = false;
        }

        if (postViewMode == ChanSettings.PostViewMode.CARD) {
            postAdapter.setCompact(compactMode);
            if (layoutManager instanceof GridLayoutManager glm) {
                if (glm.getSpanCount() != spanCount) {
                    glm.setSpanCount(spanCount);
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Calculation of span count moved to onSizeChanged to prevent re-layout loops during measurement
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void setPostViewMode(ChanSettings.PostViewMode postViewMode) {
        if (this.postViewMode != postViewMode) {
            this.postViewMode = postViewMode;

            layoutManager = null;

            switch (postViewMode) {
                case LIST:
                    LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getContext()) {
                        @Override
                        public boolean requestChildRectangleOnScreen(
                                @NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate,
                                boolean focusedChildVisible) {
                            return false;
                        }
                    };
                    setRecyclerViewPadding();
                    recyclerView.setLayoutManager(linearLayoutManager);
                    layoutManager = linearLayoutManager;

                    if (background != R.attr.backcolor) {
                        background = R.attr.backcolor;
                        setBackgroundColor(getAttrColor(getContext(), R.attr.backcolor));
                    }

                    break;
                case CARD:
                    GridLayoutManager gridLayoutManager = new GridLayoutManager(
                            getContext(), spanCount, GridLayoutManager.VERTICAL, false) {
                        @Override
                        public boolean requestChildRectangleOnScreen(
                                @NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect, boolean immediate,
                                boolean focusedChildVisible) {
                            return false;
                        }
                    };
                    setRecyclerViewPadding();
                    recyclerView.setLayoutManager(gridLayoutManager);
                    layoutManager = gridLayoutManager;

                    if (background != R.attr.backcolor_secondary) {
                        background = R.attr.backcolor_secondary;
                        setBackgroundColor(getAttrColor(getContext(), R.attr.backcolor_secondary));
                    }

                    break;
            }

            recyclerView.getRecycledViewPool().clear();

            postAdapter.setPostViewMode(postViewMode);
        }
    }

    public void bindReplyLoadable(Loadable loadable) {
        reply.bindLoadable(loadable);
    }

    public void showPosts(ChanThread thread, PostsFilter filter, boolean initial) {
        if (getWidth() > 0) {
            updateSpanCount(getWidth());
        }

        boolean threadChanged = showingThread != null && showingThread != thread;
        showingThread = thread;

        // Always reset so bottom-reached can re-fire for fresh data
        lastPostCount = -1;

        if (initial || threadChanged) {
            threadLastViewed = thread.loadable.lastViewed;
            reply.bindLoadable(showingThread.loadable);

            recyclerView.getRecycledViewPool().clear();

            int index = thread.loadable.listViewIndex;
            int top = thread.loadable.listViewTop;

            if (layoutManager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(index, top);
            }

            party();
        }

        setFastScroll(true);

        final boolean wasAtBottom = !initial && !threadChanged && scrolledToBottom();

        postAdapter.setThread(thread, filter, threadLastViewed);

        recyclerView.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                if (postAdapter.getDisplayList().isEmpty()) return true;

                if (scrolledToBottom()) {
                    int bottom = postAdapter.getDisplayList().size() - 1;
                    if (bottom > lastPostCount) {
                        lastPostCount = bottom;
                        callback.onListScrolledToBottom();
                        recyclerView.post(() -> postAdapter.clearLastSeenIndicator());
                        if (showingThread != null && !showingThread.posts.isEmpty()) {
                            threadLastViewed = showingThread.posts.get(
                                    showingThread.posts.size() - 1).no;
                        }
                    }
                } else if (wasAtBottom) {
                    // New posts pushed us above bottom so we only mark the currently visible posts as seen
                    int lastVisible = getBottomAdapterPosition();
                    if (lastVisible >= 0) {
                        int postPos = postAdapter.getPostPosition(lastVisible);
                        if (postPos < 0) postPos = postAdapter.getDisplayList().size() - 1;
                        if (postPos >= 0 && postPos < postAdapter.getDisplayList().size()) {
                            callback.onPostSeen(postAdapter.getDisplayList().get(postPos).no);
                        }
                    }
                }
                return true;
            }
        });
    }

    public boolean onBack() {
        if (reply.onBack()) {
            return true;
        } else if (replyOpen) {
            openReply(false);
            return true;
        } else {
            return false;
        }
    }

    public boolean sendKeyEvent(KeyEvent event) {
        if (ChanSettings.volumeKeysScrolling.get()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        boolean down = event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN;
                        int scroll = (int) (getHeight() * 0.75);
                        recyclerView.smoothScrollBy(0, down ? scroll : -scroll);
                    }
                    return true;
            }
        }
        return false;
    }

    public void gainedFocus() {
        showToolbarIfNeeded();
    }

    public void openReply(boolean open) {
        if (showingThread != null && replyOpen != open) {
            this.replyOpen = open;

            applyReplyLayoutMode();

            reply.animate().cancel();

            reply.measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            int height = reply.getMeasuredHeight();

            final ViewPropertyAnimator viewPropertyAnimator = reply.animate();
            viewPropertyAnimator.setListener(null);
            viewPropertyAnimator.setInterpolator(new DecelerateInterpolator(2f));
            viewPropertyAnimator.setDuration(280);
            viewPropertyAnimator.withLayer();

            boolean bottomReply = ChanSettings.bottomReply.get();
            float hiddenTranslation = bottomReply ? height : -height;

            if (open) {
                reply.setVisibility(View.VISIBLE);
                reply.setTranslationY(hiddenTranslation);
                viewPropertyAnimator.translationY(0f);
                viewPropertyAnimator.setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewPropertyAnimator.setListener(null);
                        if (replyOpen) {
                            reply.onOpen(true);
                        }
                    }
                });
            } else {
                reply.onOpen(false);
                reply.setTranslationY(0f);
                viewPropertyAnimator.translationY(hiddenTranslation);
                viewPropertyAnimator.setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewPropertyAnimator.setListener(null);
                        reply.setVisibility(View.GONE);
                    }
                });
            }

            setRecyclerViewPadding();
            if (!open) {
                AndroidUtils.hideKeyboard(reply);
            }
            threadListLayoutCallback.replyLayoutOpen(open);

            attachToolbarScroll(!(open || searchOpen));
        }
    }

    public ReplyPresenter getReplyPresenter() {
        return reply.getPresenter();
    }

    public boolean isReplyExpanded() {
        return reply != null && reply.isExpanded();
    }

    public void showError(String error) {
        postAdapter.showError(error);
    }

    public void openSearch(boolean open) {
        if (showingThread != null && searchOpen != open) {
            searchOpen = open;

            searchStatus.measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            int height = searchStatus.getMeasuredHeight();

            final ViewPropertyAnimator viewPropertyAnimator = searchStatus.animate();
            viewPropertyAnimator.setListener(null);
            viewPropertyAnimator.setInterpolator(new DecelerateInterpolator(2f));
            viewPropertyAnimator.setDuration(300);

            if (open) {
                searchStatus.setVisibility(View.VISIBLE);
                searchStatus.setTranslationY(ChanSettings.toolbarBottom.get() ? height : -height);
                viewPropertyAnimator.translationY(0f);
            } else {
                searchStatus.setTranslationY(0f);
                viewPropertyAnimator.translationY(ChanSettings.toolbarBottom.get() ? height : -height);
                viewPropertyAnimator.setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewPropertyAnimator.setListener(null);
                        searchStatus.setVisibility(View.GONE);
                    }
                });
            }

            setRecyclerViewPadding();
            if (open) {
                searchStatus.setText(R.string.search_empty);
            } else {
                threadListLayoutCallback.getToolbar().closeSearch();
            }

            attachToolbarScroll(!(open || replyOpen));
        }
    }

    public void setSearchStatus(String query, boolean setEmptyText, boolean hideKeyboard) {
        if (hideKeyboard) {
            AndroidUtils.hideKeyboard(this);
        }

        if (setEmptyText) {
            searchStatus.setText(R.string.search_empty);
        }

        if (query != null) {
            int size = getDisplayingPosts().size();
            searchStatus.setText(getContext().getString(R.string.search_results,
                    getContext().getResources().getQuantityString(R.plurals.posts, size, size),
                    query));
        }
    }

    public boolean canChildScrollUp() {
        if (replyOpen || searchOpen) {
            return true;
        }

        int targetTop = ChanSettings.toolbarBottom.get() ? 0 : toolbarHeight();

        switch (postViewMode) {
            case LIST:
                if (getTopAdapterPosition() == 0) {
                    View top = layoutManager.findViewByPosition(0);
                    return top != null && top.getTop() != targetTop;
                }
                break;
            case CARD:
                if (getTopAdapterPosition() == 0) {
                    View top = layoutManager.findViewByPosition(0);
                    if (top != null) {
                        if (top instanceof PostStubCell) {
                            // PostStubCell does not have grid_card_margin
                            return top.getTop() != targetTop + dp(1);
                        } else {
                            return top.getTop() != getDimen(getContext(), R.dimen.grid_card_margin) + dp(1) + targetTop;
                        }
                    }
                }
                break;
        }
        return true;
    }

    public boolean scrolledToBottom() {
        if (postAdapter == null || postAdapter.getDisplayList().isEmpty()) return false;

        if (!recyclerView.canScrollVertically(1)) {
            return true;
        }

        // Check if the last real post is at least partially visible
        int lastPostAdapterPos = postAdapter.getScrollPosition(
                postAdapter.getDisplayList().size() - 1);
        View lastPostView = layoutManager.findViewByPosition(lastPostAdapterPos);
        return lastPostView != null;
    }

    public void cleanup() {
        postAdapter.cleanup();
        reply.cleanup();
        openReply(false);
        openSearch(false);
        showingThread = null;
        lastPostCount = -1;
        threadLastViewed = -1;
        noParty();
    }

    public List<Post> getDisplayingPosts() {
        return postAdapter.getDisplayList();
    }

    public ThumbnailView getThumbnail(PostImage postImage) {
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();

        for (int i = 0; i < Objects.requireNonNull(layoutManager).getChildCount(); i++) {
            View view = layoutManager.getChildAt(i);
            if (view instanceof PostCellInterface postView) {
                Post post = postView.getPost();
                if (post == null) continue;

                if (!post.images.isEmpty()) {
                    for (PostImage image : post.images) {
                        if (image.equalUrl(postImage)) {
                            return postView.getThumbnailView(postImage);
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<ThumbnailView> getThumbnails() {
        List<ThumbnailView> thumbnails = new ArrayList<>(7);
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();

        for (int i = 0; i < Objects.requireNonNull(layoutManager).getChildCount(); i++) {
            View view = layoutManager.getChildAt(i);
            if (view instanceof PostCellInterface postView) {
                Post post = postView.getPost();
                if (post == null) continue;

                if (!post.images.isEmpty()) {
                    for (PostImage image : post.images) {
                        thumbnails.add(postView.getThumbnailView(image));
                    }
                }
            }
        }
        return thumbnails;
    }

    public void restoreScrollPosition(int index, int topOffset) {
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(index, topOffset);
        }
    }

    public void scrollToTopOf(int displayPosition) {
        if (replyOpen) {
            // Re-measure the reply layout so the padding reflects its current height
            // (e.g. after an image has been attached and the form grew taller).
            setRecyclerViewPadding();
        }
        int scrollPosition = postAdapter.getScrollPosition(displayPosition);
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(scrollPosition, 0);
        } else {
            recyclerView.scrollToPosition(scrollPosition);
        }
    }

    public void scrollTo(int displayPosition, boolean smooth) {
        if (displayPosition < 0) {
            int bottom = postAdapter.getItemCount() - 1;
            int difference = Math.abs(bottom - getTopAdapterPosition());
            if (difference > MAX_SMOOTH_SCROLL_DISTANCE) {
                smooth = false;
            }

            if (smooth) {
                recyclerView.smoothScrollToPosition(bottom);
            } else {
                recyclerView.scrollToPosition(bottom);
                // No animation means no animation, wait for the layout to finish and skip all animations
                final RecyclerView.ItemAnimator itemAnimator = recyclerView.getItemAnimator();
                AndroidUtils.waitForLayout(recyclerView, view -> {
                    if (itemAnimator != null) {
                        itemAnimator.endAnimations();
                    }
                    return true;
                });
            }
        } else {
            int scrollPosition = postAdapter.getScrollPosition(displayPosition);

            int difference = Math.abs(scrollPosition - getTopAdapterPosition());
            if (difference > MAX_SMOOTH_SCROLL_DISTANCE) {
                smooth = false;
            }

            if (smooth) {
                recyclerView.smoothScrollToPosition(scrollPosition);
            } else {
                recyclerView.scrollToPosition(scrollPosition);
                // No animation means no animation, wait for the layout to finish and skip all animations
                final RecyclerView.ItemAnimator itemAnimator = recyclerView.getItemAnimator();
                AndroidUtils.waitForLayout(recyclerView, view -> {
                    if (itemAnimator != null) {
                        itemAnimator.endAnimations();
                    }
                    return true;
                });
            }
        }
    }

    public void highlightPost(Post post) {
        postAdapter.highlightPost(post);
    }

    public void highlightPostId(String id) {
        postAdapter.highlightPostId(id);
    }

    public void highlightPostTripcode(String tripcode) {
        postAdapter.highlightPostTripcode(tripcode);
    }

    public void selectPost(int post) {
        postAdapter.selectPost(post);
    }

    @Override
    public void highlightPostNo(int no) {
        postAdapter.highlightPostNo(no);
    }

    @Override
    public void showThread(Loadable loadable) {
        callback.showThread(loadable);
    }

    @Override
    public void onReplyPageChanged() {
        threadListLayoutCallback.replyLayoutChanged();
    }

    @Override
    public void requestNewPostLoad() {
        callback.requestNewPostLoad();
    }

    @Override
    public ChanThread getThread() {
        return showingThread;
    }

    @Override
    public void showImageReencodingWindow() {
        threadListLayoutCallback.showImageReencodingWindow();
    }

    public int[] getIndexAndTop() {
        int index = 0;
        int top = 0;
        if (recyclerView.getLayoutManager() != null
                && recyclerView.getLayoutManager().getChildCount() > 0) {
            View topChild = recyclerView.getLayoutManager().getChildAt(0);

            if (topChild != null) {
                index = ((RecyclerView.LayoutParams) topChild.getLayoutParams()).getViewLayoutPosition();

                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) topChild.getLayoutParams();
                top = layoutManager.getDecoratedTop(topChild) - params.topMargin - recyclerView.getPaddingTop();
            }
        }

        return new int[]{index, top};
    }

    private void attachToolbarScroll(boolean attach) {
        if (threadListLayoutCallback.shouldToolbarCollapse()) {
            Toolbar toolbar = threadListLayoutCallback.getToolbar();
            if (attach) {
                toolbar.attachRecyclerViewScrollStateListener(recyclerView);
            } else {
                toolbar.detachRecyclerViewScrollStateListener(recyclerView);
                toolbar.collapseShow(true);
            }
        }
    }

    private void showToolbarIfNeeded() {
        if (threadListLayoutCallback.shouldToolbarCollapse()) {
            // Of coming back to focus from a dual controller, like the threadlistcontroller,
            // check if we should show the toolbar again (after the other controller made it hide).
            // It should show if the search or reply is open, or if the thread was scrolled at the
            // top showing an empty space.

            Toolbar toolbar = threadListLayoutCallback.getToolbar();
            if (searchOpen || replyOpen) {
                // force toolbar to show
                toolbar.collapseShow(true);
            } else {
                // check if it should show if it was scrolled at the top
                toolbar.checkToolbarCollapseState(recyclerView);
            }
        }
    }

    private void setFastScroll(boolean enabled) {
        if (!enabled) {
            if (fastScroller != null) {
                recyclerView.removeItemDecoration(fastScroller);
                fastScroller = null;
            }
        } else {
            if (fastScroller == null) {
                fastScroller = FastScrollerHelper.create(recyclerView);
            }
        }

        recyclerView.setVerticalScrollBarEnabled(!enabled);
    }

    private void setRecyclerViewPadding() {
        int defaultPadding = 0;
        if (postViewMode == ChanSettings.PostViewMode.CARD) {
            defaultPadding = dp(1);
        }

        int left = defaultPadding;
        int top = defaultPadding;
        int right = defaultPadding;
        int bottom = defaultPadding;

        boolean bottomReply = ChanSettings.bottomReply.get();
        boolean topOverlayConsumesToolbar = !ChanSettings.toolbarBottom.get()
                && ((replyOpen && !bottomReply) || searchOpen);
        boolean bottomOverlayConsumesToolbar = ChanSettings.toolbarBottom.get()
                && searchOpen;

        if (ChanSettings.toolbarBottom.get()) {
            if (!bottomOverlayConsumesToolbar) {
                bottom += toolbarHeight();
            }
        } else if (!topOverlayConsumesToolbar) {
            top += toolbarHeight();
        }

        if (replyOpen) {
            reply.measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            if (bottomReply) {
                bottom += reply.getMeasuredHeight();
            } else {
                top += reply.getMeasuredHeight();
            }
        } else if (searchOpen) {
            searchStatus.measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            if (ChanSettings.toolbarBottom.get()) {
                bottom += searchStatus.getMeasuredHeight();
            } else {
                top += searchStatus.getMeasuredHeight();
            }
        }

        recyclerView.setPadding(left, top, right, bottom);
    }

    private int toolbarHeight() {
        Toolbar toolbar = threadListLayoutCallback.getToolbar();
        return toolbar.getToolbarHeight();
    }

    private void applyReplyLayoutMode() {
        FrameLayout.LayoutParams replyLayoutParams = (FrameLayout.LayoutParams) reply.getLayoutParams();
        int targetGravity = ChanSettings.bottomReply.get() ? android.view.Gravity.BOTTOM : android.view.Gravity.TOP;
        int targetTopMargin = ChanSettings.bottomReply.get() && !ChanSettings.toolbarBottom.get()
            ? toolbarHeight()
            : 0;
        int targetBottomMargin = ChanSettings.bottomReply.get() && ChanSettings.toolbarBottom.get()
            ? toolbarHeight()
            : 0;

        if (replyLayoutParams.gravity != targetGravity
            || replyLayoutParams.topMargin != targetTopMargin
            || replyLayoutParams.bottomMargin != targetBottomMargin) {
            replyLayoutParams.gravity = targetGravity;
            replyLayoutParams.topMargin = targetTopMargin;
            replyLayoutParams.bottomMargin = targetBottomMargin;
            reply.setLayoutParams(replyLayoutParams);
        }

        int targetTopPadding = ChanSettings.toolbarBottom.get() || ChanSettings.bottomReply.get()
            ? 0
            : toolbarHeight();
        if (reply.getPaddingTop() != targetTopPadding
            || reply.getPaddingLeft() != 0
            || reply.getPaddingRight() != 0
            || reply.getPaddingBottom() != 0) {
            reply.setPadding(0, targetTopPadding, 0, 0);
        }
    }

    public int getReplyHeight() {
        reply.measure(
                MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        );
        return reply.getMeasuredHeight();
    }

    public int getTopAdapterPosition() {
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager).findFirstVisibleItemPosition();
        }
        return 0;
    }

    private int getBottomAdapterPosition() {
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager).findLastVisibleItemPosition();
        }
        return 0;
    }

    private int getCompleteBottomAdapterPosition() {
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager).findLastCompletelyVisibleItemPosition();
        }
        return 0;
    }

    private Bitmap hat;

    private final RecyclerView.ItemDecoration party = new RecyclerView.ItemDecoration() {
        @Override
        public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            if (hat == null) {
                hat = BitmapFactory.decodeResource(getResources(), R.drawable.partyhat);
            }

            for (int i = 0, j = parent.getChildCount(); i < j; i++) {
                View child = parent.getChildAt(i);
                if (child instanceof PostCellInterface postView) {
                    Post post = postView.getPost();
                    if (post.isOP && !post.images.isEmpty()) {
                        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child.getLayoutParams();
                        int top = child.getTop() + params.topMargin;
                        int left = child.getLeft() + params.leftMargin;
                        c.drawBitmap(hat, left - parent.getPaddingLeft() - dp(25), top - dp(80) - parent.getPaddingTop() + toolbarHeight(), null);
                    }
                }
            }
        }
    };

    private void party() {
        if (showingThread.loadable.site instanceof Chan4) {
            Calendar calendar = Calendar.getInstance();
            if (calendar.get(Calendar.MONTH) == Calendar.OCTOBER && calendar.get(Calendar.DAY_OF_MONTH) == 1) {
                recyclerView.addItemDecoration(party);
            }
        }
    }

    private void noParty() {
        recyclerView.removeItemDecoration(party);
    }

    public void onImageOptionsApplied(Reply _reply) {
        reply.onImageOptionsApplied(_reply);
    }

    public interface ThreadListLayoutPresenterCallback {
        void showThread(Loadable loadable);

        void requestNewPostLoad();

        void onListScrolledToBottom();

        void onPostSeen(int postNo);
    }

    public interface ThreadListLayoutCallback {
        void replyLayoutOpen(boolean open);

        void replyLayoutChanged();

        Toolbar getToolbar();

        boolean shouldToolbarCollapse();

        void showImageReencodingWindow();

        void onScrolling(int dy);
    }
}
