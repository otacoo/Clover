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

import static org.otacoo.chan.utils.AndroidUtils.ROBOTO_CONDENSED_REGULAR;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.setRoundItemBackground;
import static org.otacoo.chan.utils.AndroidUtils.sp;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.layout.FixedRatioLinearLayout;
import org.otacoo.chan.ui.text.FastTextView;
import org.otacoo.chan.ui.theme.Theme;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.ui.view.FloatingMenu;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.PostImageThumbnailView;
import org.otacoo.chan.ui.view.ThumbnailView;

import java.util.ArrayList;
import java.util.List;

public class CardPostCell extends CardView implements PostCellInterface, View.OnClickListener {
    private static final int COMMENT_MAX_LENGTH = 200;

    private boolean bound;
    private Theme theme;
    private Post post;
    private PostCellInterface.PostCellCallback callback;
    private boolean compact = false;

    private FixedRatioLinearLayout content;
    private PostImageThumbnailView thumbnailView;
    private TextView title;
    private FastTextView comment;
    private TextView replies;
    private PostIcons icons;
    private ImageView options;
    private View filterMatchColor;

    public CardPostCell(Context context) {
        super(context);
    }

    public CardPostCell(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CardPostCell(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        content = findViewById(R.id.card_content);
        content.setRatio(9f / 18f);
        thumbnailView = findViewById(R.id.thumbnail);
        thumbnailView.setRatio(16f / 13f);
        thumbnailView.setOnClickListener(this);
        title = findViewById(R.id.title);
        comment = findViewById(R.id.comment);
        replies = findViewById(R.id.replies);
        icons = findViewById(R.id.icons);
        options = findViewById(R.id.options);
        setRoundItemBackground(options);
        filterMatchColor = findViewById(R.id.filter_match_color);

        setOnClickListener(this);

        setCompact(compact);

        options.setOnClickListener(v -> {
            List<FloatingMenuItem> items = new ArrayList<>();
            List<FloatingMenuItem> extraItems = new ArrayList<>();
            Object extraOption = callback.onPopulatePostOptions(post, items, extraItems);
            showOptions(v, items, extraItems, extraOption);
        });
    }

    private void showOptions(View anchor, List<FloatingMenuItem> items,
                             List<FloatingMenuItem> extraItems,
                             Object extraOption) {
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
            }
        });
        menu.show();
    }

    @Override
    public void onClick(View v) {
        if (v == thumbnailView) {
            if (post.image() != null && !post.fileDeleted) {
                callback.onThumbnailClicked(post, post.image(), thumbnailView);
            }
        } else if (v == this) {
            callback.onPostClicked(post);
        }
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
            thumbnailView.setOnNetworkErrorListener(code -> {
                if (code == 404 && post != null && !post.deleted.get()) {
                    post.deleted.set(true);
                    bindPost(theme, post);
                }
            });
            bindPost(theme, post);
        }
    }

    public void setPost(Theme theme, final Post post, PostCellInterface.PostCellCallback callback,
                        boolean selectable, boolean highlighted, boolean selected, int markedNo,
                        boolean showDivider, ChanSettings.PostViewMode postViewMode,
                        boolean compact) {
        if (this.post == post) {
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
        
        thumbnailView.setOnNetworkErrorListener(code -> {
            if (code == 404 && this.post != null && !this.post.deleted.get()) {
                this.post.deleted.set(true);
                bindPost(this.theme, this.post);
            }
        });

        bindPost(theme, post);

        this.compact = compact;
        setCompact(compact);
    }

    public Post getPost() {
        return post;
    }

    public ThumbnailView getThumbnailView(PostImage postImage) {
        return thumbnailView;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void bindPost(Theme theme, Post post) {
        bound = true;

        if (post.deleted.get()) {
            options.setVisibility(View.GONE);
            filterMatchColor.setVisibility(View.GONE);
            replies.setVisibility(View.GONE);
            icons.setVisibility(View.GONE);

            if (!ChanSettings.textOnly.get()) {
                thumbnailView.setVisibility(View.VISIBLE);
                thumbnailView.setClickable(false);
                thumbnailView.setImageDrawable(get404FileDrawable());
                thumbnailView.setPostImage(null, 0, 0);
                thumbnailView.setLabelText(null);
                thumbnailView.setFallbackDrawable(null);
            } else {
                thumbnailView.setVisibility(View.GONE);
            }
        } else {
            options.setVisibility(View.VISIBLE);
            replies.setVisibility(View.VISIBLE);
            icons.setVisibility(View.VISIBLE);

            if (post.image() != null && !ChanSettings.textOnly.get()) {
                thumbnailView.setVisibility(View.VISIBLE);
                thumbnailView.setClickable(!post.fileDeleted);
                if (post.fileDeleted) {
                    thumbnailView.setFallbackDrawable(getDeletedFileDrawable());
                    thumbnailView.setPostImage(post.image(), thumbnailView.getWidth(), thumbnailView.getHeight(), true);
                } else {
                    thumbnailView.setFallbackDrawable(null);
                    thumbnailView.setPostImage(post.image(), thumbnailView.getWidth(), thumbnailView.getHeight());
                }
                thumbnailView.setLabelText(null);
            } else {
                thumbnailView.setPostImage(null, 0, 0);
                if (post.fileDeleted && !ChanSettings.textOnly.get()) {
                    thumbnailView.setVisibility(View.VISIBLE);
                    thumbnailView.setClickable(false);
                    thumbnailView.setImageDrawable(getDeletedFileDrawable());
                    thumbnailView.setLabelText(null);
                } else {
                    thumbnailView.setVisibility(View.GONE);
                    thumbnailView.setLabelText(null);
                    thumbnailView.setClickable(false);
                }
            }
        }

        if (post.filterHighlightedColor != 0 && !post.deleted.get()) {
            filterMatchColor.setVisibility(View.VISIBLE);
            filterMatchColor.setBackgroundColor(post.filterHighlightedColor);
        } else {
            filterMatchColor.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(post.subjectSpan)) {
            title.setVisibility(View.VISIBLE);
            if (!TextUtils.equals(title.getText(), post.subjectSpan)) {
                title.setText(post.subjectSpan);
            }
        } else {
            title.setVisibility(View.GONE);
            if (!TextUtils.equals(title.getText(), "")) {
                title.setText(null);
            }
        }

        CharSequence commentText;
        if (post.comment.length() > COMMENT_MAX_LENGTH) {
            commentText = post.comment.subSequence(0, COMMENT_MAX_LENGTH);
        } else {
            commentText = post.comment;
        }

        comment.setVisibility(TextUtils.isEmpty(commentText) ? View.GONE : View.VISIBLE);
        comment.setText(commentText);
        comment.setTextColor(theme.textPrimary);

        String statsStr = getResources().getString(R.string.card_stats, post.getReplies(), post.getImagesCount());
        if (!TextUtils.equals(replies.getText(), statsStr)) {
            replies.setText(statsStr);
        }

        icons.edit();
        icons.set(PostIcons.STICKY, post.isSticky());
        icons.set(PostIcons.CLOSED, post.isClosed());
        icons.set(PostIcons.DELETED, post.deleted.get());
        icons.set(PostIcons.ARCHIVED, post.isArchived());

        boolean showFlags = true;
        ChanSettings.HideFlagsMode hideFlagsMode = ChanSettings.hideFlags.get();
        boolean threadMode = callback.getLoadable().isThreadMode();
        if (hideFlagsMode == ChanSettings.HideFlagsMode.ALL) {
            showFlags = false;
        } else if (hideFlagsMode == ChanSettings.HideFlagsMode.THREAD && threadMode) {
            showFlags = false;
        } else if (hideFlagsMode == ChanSettings.HideFlagsMode.CATALOG && !threadMode) {
            showFlags = false;
        }

        icons.set(PostIcons.HTTP_ICONS_COMPACT, showFlags && post.httpIcons != null);

        if (showFlags && post.httpIcons != null) {
            icons.setHttpIcons(post.httpIcons, theme, icons.getHeight());
        }

        icons.apply();
    }

    private Drawable get404FileDrawable() {
        int drawableRes = theme != null && theme.isLightTheme
                ? R.drawable.ic_404_black
                : R.drawable.ic_404_white;
        Drawable drawable = ContextCompat.getDrawable(getContext(), drawableRes);
        return drawable != null ? drawable.mutate() : null;
    }

    private Drawable getDeletedFileDrawable() {
        int drawableRes = theme != null && theme.isLightTheme
                ? R.drawable.ic_file_deleted_black
                : R.drawable.ic_file_deleted_white;
        Drawable drawable = ContextCompat.getDrawable(getContext(), drawableRes);
        return drawable != null ? drawable.mutate() : null;
    }

    private void unbindPost(Post post) {
        bound = false;

        icons.cancelRequests();
    }

    private void setCompact(boolean compact) {
        int textReduction = compact ? -2 : 0;
        int textSizeSp = Integer.parseInt(ChanSettings.fontSize.get()) + textReduction;
        title.setTextSize(textSizeSp);
        if (ChanSettings.fontCondensed.get()) {
            title.setTypeface(ROBOTO_CONDENSED_REGULAR);
            comment.setTypeface(ROBOTO_CONDENSED_REGULAR);
            replies.setTypeface(ROBOTO_CONDENSED_REGULAR);
        } else {
            title.setTypeface(Typeface.DEFAULT);
            comment.setTypeface(null);
            replies.setTypeface(Typeface.DEFAULT);
        }
        comment.setTextSize(textSizeSp);
        replies.setTextSize(textSizeSp);
        icons.setHeight(sp(textSizeSp));
        icons.setSpacing(dp(4));
        icons.setPadding(0, 0, 0, dp(4));

        int p = compact ? dp(3) : dp(8);

        // Same as the layout.
        title.setPadding(p, p, p, 0);
        comment.setPadding(p, p, p, 0);
        replies.setPadding(p, p / 2, p, p);

        int optionsPadding = compact ? 0 : dp(5);
        options.setPadding(0, optionsPadding, optionsPadding, 0);
    }
}
