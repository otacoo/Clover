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
package org.otacoo.chan.ui.cell;

import static android.text.TextUtils.isEmpty;
import static org.otacoo.chan.utils.AndroidUtils.ROBOTO_CONDENSED_REGULAR;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getString;
import static org.otacoo.chan.utils.AndroidUtils.openIntent;
import static org.otacoo.chan.utils.AndroidUtils.setRoundItemBackground;
import static org.otacoo.chan.utils.AndroidUtils.sp;

import android.annotation.TargetApi;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.model.PostLinkable;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.helper.PostHelper;
import org.otacoo.chan.ui.span.AbsoluteSizeSpanHashed;
import org.otacoo.chan.ui.span.ForegroundColorSpanHashed;
import org.otacoo.chan.ui.text.FastTextView;
import org.otacoo.chan.ui.text.FastTextViewMovementMethod;
import org.otacoo.chan.ui.theme.Theme;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.ui.view.FloatingMenu;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.PostImageThumbnailView;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Time;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

public class PostCell extends LinearLayout implements PostCellInterface {
    private static final String TAG = "PostCell";
    private static final int COMMENT_MAX_LENGTH_BOARD = 350;

    private List<PostImageThumbnailView> thumbnailViews = new ArrayList<>(1);

    private RelativeLayout relativeLayoutContainer;
    private RelativeLayout relativeLayoutHelper;
    private FastTextView title;
    private PostIcons icons;
    private TextView comment;
    private FastTextView replies;
    private View repliesAdditionalArea;
    private ImageView options;
    private View divider;
    private View filterMatchColor;

    private int detailsSizePx;
    private int iconSizePx;
    private int paddingPx;
    private boolean threadMode;
    private boolean ignoreNextOnClick;

    private boolean bound = false;
    private Theme theme;
    private Post post;
    private PostCellCallback callback;
    private boolean selectable;
    private boolean highlighted;
    private boolean selected;
    private int markedNo;
    private boolean showDivider;

    private OnClickListener selfClicked = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (ignoreNextOnClick) {
                ignoreNextOnClick = false;
            } else {
                callback.onPostClicked(post);
            }
        }
    };
    private PostViewMovementMethod commentMovementMethod = new PostViewMovementMethod();
    private PostViewFastMovementMethod titleMovementMethod = new PostViewFastMovementMethod();

    private boolean viewHolderSetupDone = false;
    private boolean setupThreadMode = false;
    private boolean setupSelectable = false;
    private final ActionMode.Callback selectionCallback = new ActionMode.Callback() {
        private MenuItem quoteMenuItem;
        private MenuItem webSearchItem;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            quoteMenuItem = menu.add(Menu.NONE, R.id.post_selection_action_quote, 0, R.string.post_quote);
            webSearchItem = menu.add(Menu.NONE, R.id.post_selection_action_search, 1, R.string.post_web_search);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item == null) return false;
            // ensure start and end are in the right order
            int start = Math.min(comment.getSelectionEnd(), comment.getSelectionStart());
            int end = Math.max(comment.getSelectionEnd(), comment.getSelectionStart());
            if (start < 0 || end < 0 || start > comment.getText().length() || end > comment.getText().length()) {
                return false;
            }
            CharSequence selection = comment.getText().subSequence(start, end);
            if (item.getItemId() == quoteMenuItem.getItemId()) {
                callback.onPostSelectionQuoted(post, selection);
                mode.finish();
                return true;
            } else if (item.getItemId() == webSearchItem.getItemId()) {
                Intent searchIntent = new Intent(Intent.ACTION_WEB_SEARCH);
                searchIntent.putExtra(SearchManager.QUERY, selection.toString());
                openIntent(searchIntent);
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }
    };

    public PostCell(Context context) {
        super(context);
    }

    public PostCell(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PostCell(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        relativeLayoutContainer = findViewById(R.id.relative_layout_container);
        relativeLayoutHelper = findViewById(R.id.relative_layout_helper);
        title = findViewById(R.id.title);
        icons = findViewById(R.id.icons);
        comment = findViewById(R.id.comment);
        replies = findViewById(R.id.replies);
        repliesAdditionalArea = findViewById(R.id.replies_additional_area);
        options = findViewById(R.id.options);
        divider = findViewById(R.id.divider);
        filterMatchColor = findViewById(R.id.filter_match_color);

        int textSizeSp = Integer.parseInt(ChanSettings.fontSize.get());
        paddingPx = dp(textSizeSp - 7);
        detailsSizePx = sp(textSizeSp - 4);
        title.setTextSize(textSizeSp);
        title.setPadding(paddingPx, paddingPx, dp(52), 0);

        iconSizePx = sp(textSizeSp - 3);
        icons.setHeight(sp(textSizeSp));
        icons.setSpacing(dp(4));
        icons.setPadding(paddingPx, dp(4), paddingPx, 0);

        comment.setTextSize(textSizeSp);
        comment.setPadding(paddingPx, paddingPx, paddingPx, 0);

        if (ChanSettings.fontCondensed.get()) {
            title.setTypeface(ROBOTO_CONDENSED_REGULAR);
            comment.setTypeface(ROBOTO_CONDENSED_REGULAR);
            replies.setTypeface(ROBOTO_CONDENSED_REGULAR);
        }

        replies.setTextSize(textSizeSp);
        replies.setPadding(paddingPx, 0, paddingPx, paddingPx);

        setRoundItemBackground(replies);
        setRoundItemBackground(options);

        RelativeLayout.LayoutParams dividerParams = (RelativeLayout.LayoutParams) divider.getLayoutParams();
        dividerParams.leftMargin = paddingPx;
        dividerParams.rightMargin = paddingPx;
        divider.setLayoutParams(dividerParams);

        OnClickListener repliesClickListener = v -> {
            if (replies.getVisibility() != VISIBLE || !threadMode) {
                return;
            }
            int repliesFromSize;
            synchronized (post.repliesFrom) {
                repliesFromSize = post.repliesFrom.size();
            }

            if (repliesFromSize > 0) {
                callback.onShowPostReplies(post);
            }
        };
        replies.setOnClickListener(repliesClickListener);
        repliesAdditionalArea.setOnClickListener(repliesClickListener);

        options.setOnClickListener(v -> {
            List<FloatingMenuItem> items = new ArrayList<>();
            List<FloatingMenuItem> extraItems = new ArrayList<>();
            Object extraOption = callback.onPopulatePostOptions(post, items, extraItems);
            showOptions(v, items, extraItems, extraOption);
        });

        setOnClickListener(selfClicked);
    }

    private void showOptions(View anchor, List<FloatingMenuItem> items,
                             List<FloatingMenuItem> extraItems,
                             Object extraOption) {
        if (ThemeHelper.getInstance().getTheme().isLightTheme) {
            options.setImageResource(R.drawable.ic_overflow_black);
        }

        FloatingMenu menu = new FloatingMenu(getContext(), anchor, items);
        menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
            @Override
            public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                if (item.getId() == extraOption) {
                    showOptions(anchor, extraItems, null, null);
                }

                callback.onPostOptionClicked(post, item.getId());
            }

            @Override
            public void onFloatingMenuDismissed(FloatingMenu menu) {
                options.setImageResource(R.drawable.ic_overflow);
            }
        });
        menu.show();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (post != null && bound) {
            unbindPost(post);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (post != null && !bound) {
            bindPost(theme, post);
        }
    }

    public void setPost(Theme theme,
                        final Post post,
                        PostCellInterface.PostCellCallback callback,
                        boolean selectable,
                        boolean highlighted,
                        boolean selected,
                        int markedNo,
                        boolean showDivider,
                        ChanSettings.PostViewMode postViewMode,
                        boolean compact) {
        if (this.post == post &&
                this.selectable == selectable &&
                this.highlighted == highlighted &&
                this.selected == selected &&
                this.markedNo == markedNo &&
                this.showDivider == showDivider) {
            return;
        }

        if (theme == null) {
            theme = ThemeHelper.theme();
        }

        if (this.post != null && bound) {
            unbindPost(this.post);
            this.post = null;
        }

        this.theme = theme;
        this.post = post;
        this.callback = callback;
        this.selectable = selectable;
        this.highlighted = highlighted;
        this.selected = selected;
        this.markedNo = markedNo;
        this.showDivider = showDivider;

        bindPost(theme, post);
    }

    public Post getPost() {
        return post;
    }

    public ThumbnailView getThumbnailView(PostImage postImage) {
        for (int i = 0; i < post.images.size(); i++) {
            if (post.images.get(i).equalUrl(postImage)) {
                return ChanSettings.textOnly.get() ? null : thumbnailViews.get(i);
            }
        }

        return null;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void bindPost(Theme theme, Post post) {
        bound = true;

        threadMode = callback.getLoadable().isThreadMode();

        setPostLinkableListener(post, true);

        replies.setClickable(threadMode);
        repliesAdditionalArea.setClickable(threadMode);

        if (!threadMode) {
            replies.setBackgroundResource(0);
        }

        if (highlighted) {
            setBackgroundColor(theme.highlightedColor);
        } else if (post.isSavedReply) {
            setBackgroundColor(theme.savedReplyColor);
        } else if (selected) {
            setBackgroundColor(theme.selectedColor);
        } else if (threadMode) {
            setBackgroundResource(0);
        } else {
            setBackgroundResource(R.drawable.item_background);
        }

        if (post.filterHighlightedColor != 0) {
            filterMatchColor.setVisibility(View.VISIBLE);
            filterMatchColor.setBackgroundColor(post.filterHighlightedColor);
        } else {
            filterMatchColor.setVisibility(View.GONE);
        }

        buildThumbnails();

        // Align the replies row with the text column.
        // layout_toRightOf the thumbnail; replicate that indent via leftMargin instead.
        RelativeLayout.LayoutParams repliesLp = (RelativeLayout.LayoutParams) replies.getLayoutParams();
        if (ChanSettings.layoutTextBelowThumbnails.get()) {
            repliesLp.leftMargin = 0;
        } else if (!thumbnailViews.isEmpty()) {
            int thumbSize = ChanSettings.thumbnailScale.get() * getResources()
                    .getDimensionPixelSize(R.dimen.cell_post_thumbnail_size) / 100;
            repliesLp.leftMargin = paddingPx + thumbSize;
        } else {
            repliesLp.leftMargin = 0;
        }
        replies.setLayoutParams(repliesLp);

        SpannableStringBuilder titleBuilder = new SpannableStringBuilder();

        if (post.subjectSpan != null) {
            titleBuilder.append(post.subjectSpan);
            titleBuilder.append("\n");
        }

        titleBuilder.append(post.nameTripcodeIdCapcodeSpan);

        CharSequence time;
        if (ChanSettings.postFullDate.get()) {
            time = PostHelper.getLocalDate(post);
        } else {
            // Disabled for performance reasons
            // Force the relative date to use the english locale, and restore the previous one.
            /*Configuration c = Resources.getSystem().getConfiguration();
            Locale previousLocale = c.locale;
            c.locale = Locale.ENGLISH;
            Resources.getSystem().updateConfiguration(c, null);
            time = DateUtils.getRelativeTimeSpanString(post.time * 1000L, Time.get(), DateUtils.SECOND_IN_MILLIS, 0);
            c.locale = previousLocale;
            Resources.getSystem().updateConfiguration(c, null);*/
            time = DateUtils.getRelativeTimeSpanString(post.time * 1000L, Time.get(), DateUtils.SECOND_IN_MILLIS, 0);
        }

        String noText = "#" + post.no;
        SpannableString date = new SpannableString(noText + " " + time);
        date.setSpan(new ForegroundColorSpanHashed(theme.detailsColor), 0, date.length(), 0);
        date.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, date.length(), 0);

        boolean noClickable = ChanSettings.tapNoReply.get();
        if (noClickable) {
            date.setSpan(new NoClickableSpan(), 0, noText.length(), 0);
        }

        titleBuilder.append(date);

        if (!post.images.isEmpty()) {
            boolean postFileName = ChanSettings.postFilename.get();
            boolean postFileInfo = ChanSettings.postFileInfo.get();
            for (int i = 0; i < post.images.size(); i++) {
                if (postFileName && post.fileNameSpans != null) {
                    titleBuilder.append(post.fileNameSpans[i]);
                }
                if (postFileInfo && post.fileInfoSpans != null) {
                    titleBuilder.append(post.fileInfoSpans[i]);
                }
            }
        }

        title.setText(titleBuilder);

        icons.edit();
        icons.set(PostIcons.STICKY, post.isSticky());
        icons.set(PostIcons.CLOSED, post.isClosed());
        icons.set(PostIcons.DELETED, post.deleted.get());
        icons.set(PostIcons.ARCHIVED, post.isArchived());

        boolean showFlags = true;
        ChanSettings.HideFlagsMode hideFlagsMode = ChanSettings.hideFlags.get();
        if (hideFlagsMode == ChanSettings.HideFlagsMode.ALL) {
            showFlags = false;
        } else if (hideFlagsMode == ChanSettings.HideFlagsMode.THREAD && threadMode) {
            showFlags = false;
        } else if (hideFlagsMode == ChanSettings.HideFlagsMode.CATALOG && !threadMode) {
            showFlags = false;
        }

        icons.set(PostIcons.HTTP_ICONS, showFlags && post.httpIcons != null);

        if (showFlags && post.httpIcons != null) {
            icons.setHttpIcons(post.httpIcons, theme, iconSizePx);
        }

        icons.apply();

        CharSequence commentText;
        if (!threadMode && post.comment.length() > COMMENT_MAX_LENGTH_BOARD) {
            commentText = truncatePostComment(post, COMMENT_MAX_LENGTH_BOARD);
        } else {
            commentText = post.comment;
        }

        if (ChanSettings.layoutTextBelowThumbnails.get()) {
            comment.setVisibility(isEmpty(commentText) ? GONE : VISIBLE);
            RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) comment.getLayoutParams();
            int[] rules = layoutParams.getRules();
            if (thumbnailViews.size() != 1) {
                comment.setPadding(paddingPx, paddingPx, paddingPx, 0);
                rules[RelativeLayout.RIGHT_OF] = R.id.thumbnail_view;
                rules[RelativeLayout.BELOW] = R.id.icons;
                if (comment.getParent() != relativeLayoutHelper) {
                    if (comment.getParent() != null) ((ViewGroup) comment.getParent()).removeView(comment);
                    relativeLayoutHelper.addView(comment);
                }
                // replies stays in relativeLayoutContainer, anchored below the helper
                if (replies.getParent() != relativeLayoutContainer) {
                    if (replies.getParent() != null) ((ViewGroup) replies.getParent()).removeView(replies);
                    relativeLayoutContainer.addView(replies);
                }
                if (repliesAdditionalArea.getParent() != relativeLayoutContainer) {
                    if (repliesAdditionalArea.getParent() != null) ((ViewGroup) repliesAdditionalArea.getParent()).removeView(repliesAdditionalArea);
                    relativeLayoutContainer.addView(repliesAdditionalArea);
                }
                RelativeLayout.LayoutParams repliesRp = (RelativeLayout.LayoutParams) replies.getLayoutParams();
                repliesRp.getRules()[RelativeLayout.BELOW] = R.id.relative_layout_helper;
                replies.setLayoutParams(repliesRp);
            } else {
                comment.setPadding(paddingPx, 0, paddingPx, 0);
                rules[RelativeLayout.RIGHT_OF] = 0;
                rules[RelativeLayout.BELOW] = R.id.relative_layout_helper;
                if (comment.getParent() != relativeLayoutContainer) {
                    if (comment.getParent() != null) ((ViewGroup) comment.getParent()).removeView(comment);
                    relativeLayoutContainer.addView(comment);
                }
                if (replies.getParent() != relativeLayoutContainer) {
                    if (replies.getParent() != null) ((ViewGroup) replies.getParent()).removeView(replies);
                    relativeLayoutContainer.addView(replies);
                }
                if (repliesAdditionalArea.getParent() != relativeLayoutContainer) {
                    if (repliesAdditionalArea.getParent() != null) ((ViewGroup) repliesAdditionalArea.getParent()).removeView(repliesAdditionalArea);
                    relativeLayoutContainer.addView(repliesAdditionalArea);
                }
                // replies must anchor below comment when comment is in the container
                RelativeLayout.LayoutParams repliesRp = (RelativeLayout.LayoutParams) replies.getLayoutParams();
                repliesRp.getRules()[RelativeLayout.BELOW] = R.id.comment;
                replies.setLayoutParams(repliesRp);
            }
        } else {
            // Alternate layout is OFF
            if (comment.getParent() != relativeLayoutHelper) {
                RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) comment.getLayoutParams();
                int[] rules = layoutParams.getRules();
                rules[RelativeLayout.RIGHT_OF] = R.id.thumbnail_view;
                rules[RelativeLayout.BELOW] = R.id.icons;
                comment.setPadding(paddingPx, paddingPx, paddingPx, 0);
                if (comment.getParent() != null) ((ViewGroup) comment.getParent()).removeView(comment);
                relativeLayoutHelper.addView(comment);
            }
            if (replies.getParent() != relativeLayoutContainer) {
                if (replies.getParent() != null) ((ViewGroup) replies.getParent()).removeView(replies);
                relativeLayoutContainer.addView(replies);
            }
            if (repliesAdditionalArea.getParent() != relativeLayoutContainer) {
                if (repliesAdditionalArea.getParent() != null) ((ViewGroup) repliesAdditionalArea.getParent()).removeView(repliesAdditionalArea);
                relativeLayoutContainer.addView(repliesAdditionalArea);
            }
            // Restore replies to anchor below the helper (default XML rule)
            RelativeLayout.LayoutParams repliesRp = (RelativeLayout.LayoutParams) replies.getLayoutParams();
            repliesRp.getRules()[RelativeLayout.BELOW] = R.id.relative_layout_helper;
            replies.setLayoutParams(repliesRp);
        }

        if (threadMode) {
            boolean needsSetup = !viewHolderSetupDone
                    || threadMode != setupThreadMode
                    || selectable != setupSelectable;
            if (needsSetup) {
                viewHolderSetupDone = true;
                setupThreadMode = threadMode;
                setupSelectable = selectable;
                // Setting the text to selectable creates an editor, sets up a bunch of click
                // handlers and sets a movementmethod.
                // Required for the isTextSelectable check.
                // We override the text and movementmethod settings.
                if (selectable && !comment.isTextSelectable()) {
                    comment.setTextIsSelectable(true);
                }
                if (selectable) {
                    comment.setCustomSelectionActionModeCallback(selectionCallback);
                }
                // Sets focusable to auto, clickable and longclickable to true.
                comment.setMovementMethod(commentMovementMethod);
                // And this sets clickable to appropriate values again.
                comment.setOnClickListener(selfClicked);
            }

            if (selectable) {
                comment.setText(commentText, TextView.BufferType.SPANNABLE);
            } else {
                comment.setText(commentText);
            }

            if (noClickable) {
                title.setMovementMethod(titleMovementMethod);
            }
        } else {
            boolean needsSetup = !viewHolderSetupDone
                    || threadMode != setupThreadMode;
            if (needsSetup) {
                viewHolderSetupDone = true;
                setupThreadMode = threadMode;
                setupSelectable = selectable;
                comment.setOnClickListener(null);
                comment.setClickable(false);
                // Sets focusable to auto, clickable and longclickable to false.
                comment.setMovementMethod(null);
                title.setMovementMethod(null);
            }

            comment.setText(commentText);
        }

        int repliesFromSize;
        synchronized (post.repliesFrom) {
            repliesFromSize = post.repliesFrom.size();
        }

        if ((!threadMode && post.getReplies() > 0) || (repliesFromSize > 0)) {
            replies.setVisibility(View.VISIBLE);

            int replyCount = threadMode ? repliesFromSize : post.getReplies();
            String text = getResources().getQuantityString(R.plurals.reply, replyCount, replyCount);

            if (!threadMode && post.getImagesCount() > 0) {
                text += ", " + getResources().getQuantityString(R.plurals.image, post.getImagesCount(), post.getImagesCount());
            }

            replies.setText(text);
            comment.setPadding(comment.getPaddingLeft(), comment.getPaddingTop(), comment.getPaddingRight(), 0);
            replies.setPadding(replies.getPaddingLeft(), paddingPx, replies.getPaddingRight(), replies.getPaddingBottom());
        } else {
            replies.setVisibility(View.GONE);
            comment.setPadding(comment.getPaddingLeft(), comment.getPaddingTop(), comment.getPaddingRight(), paddingPx);
            replies.setPadding(replies.getPaddingLeft(), 0, replies.getPaddingRight(), replies.getPaddingBottom());
        }

        divider.setVisibility(showDivider ? VISIBLE : GONE);
    }

    private void buildThumbnails() {
        if (!post.images.isEmpty() && !ChanSettings.textOnly.get()
                && post.images.size() == thumbnailViews.size()) {
            // Reuse existing views: only update the image and click listener.
            final int size = ChanSettings.thumbnailScale.get() * getResources()
                    .getDimensionPixelSize(R.dimen.cell_post_thumbnail_size) / 100;
            for (int i = 0; i < thumbnailViews.size(); i++) {
                PostImageThumbnailView v = thumbnailViews.get(i);
                PostImage image = post.images.get(i);
                v.setPostImage(image, size, size);
                v.setOnClickListener(v2 -> callback.onThumbnailClicked(post, image, v));
            }
            return;
        }

        for (PostImageThumbnailView thumbnailView : thumbnailViews) {
            relativeLayoutHelper.removeView(thumbnailView);
        }
        thumbnailViews.clear();

        // Places the thumbnails below each other.
        // The placement is done using the RelativeLayout BELOW rule, with generated view ids.
        if (!post.images.isEmpty() && !ChanSettings.textOnly.get()) {
            int lastId = 0;
            int generatedId = 1;
            boolean first = true;
            for (PostImage image : post.images) {
                PostImageThumbnailView v = new PostImageThumbnailView(getContext());

                // Set the correct id.
                // The first thumbnail uses thumbnail_view so that the layout can offset to that.
                final int idToSet = first ? R.id.thumbnail_view : generatedId++;
                v.setId(idToSet);
                final int size = ChanSettings.thumbnailScale.get() * getResources()
                        .getDimensionPixelSize(R.dimen.cell_post_thumbnail_size) / 100;

                RelativeLayout.LayoutParams p = new RelativeLayout.LayoutParams(size, size);
                p.alignWithParent = true;

                if (!first) {
                    p.addRule(RelativeLayout.BELOW, lastId);
                }

                p.topMargin = paddingPx;
                p.leftMargin = paddingPx;
                p.bottomMargin = paddingPx;

                v.setPostImage(image, size, size);
                v.setClickable(true);
                v.setOnClickListener(v2 -> callback.onThumbnailClicked(post, image, v));
                //v.setRounding(dp(enableHighEndAnimations() ? 8 : 2));

                relativeLayoutHelper.addView(v, p);
                thumbnailViews.add(v);

                lastId = idToSet;
                first = false;
            }
        }
    }

    private void unbindPost(Post post) {
        bound = false;

        icons.cancelRequests();

        setPostLinkableListener(post, false);
    }

    private void setPostLinkableListener(Post post, boolean bind) {
        if (post.comment instanceof Spanned) {
            Spanned commentSpanned = (Spanned) post.comment;
            PostLinkable[] linkables = commentSpanned.getSpans(0, commentSpanned.length(), PostLinkable.class);
            for (PostLinkable linkable : linkables) {
                linkable.setMarkedNo(bind ? markedNo : -1);
            }

            if (!bind) {
                if (commentSpanned instanceof Spannable) {
                    Spannable commentSpannable = (Spannable) commentSpanned;
                    commentSpannable.removeSpan(BACKGROUND_SPAN);
                }
            }
        }
    }

    private CharSequence truncatePostComment(Post post, int maxCommentLength) {
        BreakIterator bi = BreakIterator.getWordInstance();
        bi.setText(post.comment.toString());
        int precedingBoundary = bi.following(maxCommentLength);
        // Fallback to old method in case the comment does not have any spaces/individual words
        CharSequence commentText = precedingBoundary > 0 ? post.comment.subSequence(0, precedingBoundary) : post.comment.subSequence(0, maxCommentLength);
        return TextUtils.concat(commentText, "\u2026"); // append ellipsis
    }

    private static BackgroundColorSpan BACKGROUND_SPAN = new BackgroundColorSpan(0x6633B5E5);

    /**
     * A MovementMethod that searches for PostLinkables.<br>
     * See {@link PostLinkable} for more information.
     */
    private class PostViewMovementMethod extends LinkMovementMethod {
        @Override
        public boolean onTouchEvent(@NonNull TextView widget, @NonNull Spannable buffer, @NonNull MotionEvent event) {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_DOWN) {
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();

                x += widget.getScrollX();
                y += widget.getScrollY();

                Layout layout = widget.getLayout();
                if (layout != null) {
                    int line = layout.getLineForVertical(y);
                    int off = layout.getOffsetForHorizontal(line, x);

                    ClickableSpan[] link = buffer.getSpans(off, off, ClickableSpan.class);

                    if (link.length > 0) {
                        ClickableSpan clickableSpan1 = link[0];
                        ClickableSpan clickableSpan2 = link.length > 1 ? link[1] : null;
                        PostLinkable linkable1 = clickableSpan1 instanceof PostLinkable ? (PostLinkable) clickableSpan1 : null;
                        PostLinkable linkable2 = clickableSpan2 instanceof PostLinkable ? (PostLinkable) clickableSpan2 : null;
                        if (action == MotionEvent.ACTION_UP) {
                            ignoreNextOnClick = true;
                            if (linkable2 == null && linkable1 != null) {
                                //regular, non-spoilered link
                                handlePostLinkableClick(widget, linkable1);
                            } else if (linkable2 != null && linkable1 != null) {
                                //spoilered link, figure out which span is the spoiler
                                if (linkable1.type == PostLinkable.Type.SPOILER && linkable1.isSpoilerVisible()) {
                                    //linkable2 is the link
                                    handlePostLinkableClick(widget, linkable2);
                                } else if (linkable2.type == PostLinkable.Type.SPOILER && linkable2.isSpoilerVisible()) {
                                    //linkable 1 is the link
                                    handlePostLinkableClick(widget, linkable1);
                                } else {
                                    //weird case where a double stack of linkables, but isn't spoilered (some 4chan stickied posts)
                                    handlePostLinkableClick(widget, linkable1);
                                }
                            }

                            //do onclick on all postlinkables afterwards, so that we don't update the spoiler state early
                            for (ClickableSpan s : link) {
                                if (s instanceof PostLinkable) {
                                    PostLinkable item = (PostLinkable) s;
                                    item.onClick(widget);
                                }
                            }

                            buffer.removeSpan(BACKGROUND_SPAN);
                        } else if (action == MotionEvent.ACTION_DOWN && clickableSpan1 instanceof PostLinkable) {
                            buffer.setSpan(BACKGROUND_SPAN, buffer.getSpanStart(clickableSpan1), buffer.getSpanEnd(clickableSpan1), 0);
                        } else if (action == MotionEvent.ACTION_CANCEL) {
                            buffer.removeSpan(BACKGROUND_SPAN);
                        }

                        return true;
                    } else {
                        buffer.removeSpan(BACKGROUND_SPAN);
                    }
                }
            }

            return super.onTouchEvent(widget, buffer, event);
        }
    }

    private void handlePostLinkableClick(View widget, PostLinkable linkable) {
        if (linkable.type == PostLinkable.Type.SJIS) {
            showSjisArt(linkable.key);
        } else {
            callback.onPostLinkableClicked(post, linkable);
        }
    }

    private void showSjisArt(CharSequence text) {
        Context context = getContext();
        
        HorizontalScrollView hsv = new HorizontalScrollView(context);
        hsv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        Typeface monaTypeface;
        try {
            monaTypeface = Typeface.createFromAsset(context.getAssets(), "font/submona.ttf");
        } catch (Exception e) {
            monaTypeface = Typeface.MONOSPACE;
        }
        
        textView.setTypeface(monaTypeface);
        textView.setTextSize(12);
        textView.setLineSpacing(0, 1.0f);
        textView.setTextColor(Color.BLACK);
        textView.setBackgroundColor(Color.WHITE);
        
        hsv.addView(textView);
        
        new AlertDialog.Builder(context)
                .setView(hsv)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /**
     * A MovementMethod that searches for PostLinkables.<br>
     * This version is for the {@link FastTextView}.<br>
     * See {@link PostLinkable} for more information.
     */
    private class PostViewFastMovementMethod implements FastTextViewMovementMethod {
        @Override
        public boolean onTouchEvent(@NonNull FastTextView widget, @NonNull Spanned buffer, @NonNull MotionEvent event) {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_UP) {
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getPaddingLeft();
                y -= widget.getPaddingTop();

                x += widget.getScrollX();
                y += widget.getScrollY();

                Layout layout = widget.getLayout();
                if (layout == null) return false;

                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);

                ClickableSpan[] link = buffer.getSpans(off, off, ClickableSpan.class);

                if (link.length != 0) {
                    if (link[0] instanceof PostLinkable) {
                        handlePostLinkableClick(widget, (PostLinkable) link[0]);
                    } else {
                        link[0].onClick(widget);
                    }
                    return true;
                }
            }

            return false;
        }
    }

    private class NoClickableSpan extends ClickableSpan {
        @Override
        public void onClick(View widget) {
            callback.onPostNoClicked(post);
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }

}
