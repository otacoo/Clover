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
package org.otacoo.chan.ui.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.otacoo.chan.R;
import org.otacoo.chan.core.site.parser.PostParser;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * A Theme<br>
 * Used for setting the toolbar color, and passed around {@link PostParser} to give the spans the correct color.<br>
 * Technically should the parser not do UI, but it is important that the spans do not get created on an UI thread for performance.
 */
public class Theme {
    public final String displayName;
    public final String name;
    public final int resValue;
    public boolean isLightTheme = true;
    public ThemeHelper.PrimaryColor primaryColor;
    public ThemeHelper.PrimaryColor accentColor;
    public ThemeHelper.PrimaryColor loadingBarColor;

    public ThemeHelper.PrimaryColor defaultPrimaryColor;
    public ThemeHelper.PrimaryColor defaultAccentColor;
    public ThemeHelper.PrimaryColor defaultLoadingBarColor;

    public int textPrimary;
    public int textSecondary;
    public int textHint;
    public int quoteColor;
    public int highlightQuoteColor;
    public int linkColor;
    public int spoilerColor;
    public int inlineQuoteColor;
    public int subjectColor;
    public int nameColor;
    public int idBackgroundLight;
    public int idBackgroundDark;
    public int capcodeColor;
    public int detailsColor;
    public int highlightedColor;
    public int savedReplyColor;
    public int selectedColor;
    public int textColorRevealSpoiler;
    
    public int backColor;
    public int backColorSecondary;
    public int dividerColor;
    public int dividerSplitColor;
    public int uiTextColor;

    public ThemeDrawable settingsDrawable;
    public ThemeDrawable imageDrawable;
    public ThemeDrawable sendDrawable;
    public ThemeDrawable clearDrawable;
    public ThemeDrawable backDrawable;
    public ThemeDrawable doneDrawable;
    public ThemeDrawable historyDrawable;
    public ThemeDrawable listAddDrawable;
    public ThemeDrawable helpDrawable;
    public ThemeDrawable refreshDrawable;
    public ThemeDrawable reorderDrawable;
    
    public Map<String, Integer> colorOverrides = new HashMap<>();

    public Theme(String displayName, String name, int resValue, ThemeHelper.PrimaryColor primaryColor) {
        this.displayName = displayName;
        this.name = name;
        this.resValue = resValue;
        this.primaryColor = primaryColor;
        accentColor = ThemeHelper.PrimaryColor.BLUE;
        loadingBarColor = primaryColor;

        resolveSpanColors();
        resolveDrawables();

        defaultPrimaryColor = this.primaryColor;
        defaultAccentColor = this.accentColor;
        defaultLoadingBarColor = this.loadingBarColor;
    }

    public void resolveDrawables() {
        if (isLightTheme) {
            settingsDrawable = new ThemeDrawable(R.drawable.ic_settings_black_24dp, 0.54f);
            imageDrawable = new ThemeDrawable(R.drawable.ic_image_black_24dp, 0.54f);
            sendDrawable = new ThemeDrawable(R.drawable.ic_send_black_24dp, 0.54f);
            clearDrawable = new ThemeDrawable(R.drawable.ic_clear_black_24dp, 0.54f);
            backDrawable = new ThemeDrawable(R.drawable.ic_arrow_back_black_24dp, 0.54f);
            doneDrawable = new ThemeDrawable(R.drawable.ic_done_black_24dp, 0.54f);
            historyDrawable = new ThemeDrawable(R.drawable.ic_history_black_24dp, 0.54f);
            listAddDrawable = new ThemeDrawable(R.drawable.ic_playlist_add_black_24dp, 0.54f);
            helpDrawable = new ThemeDrawable(R.drawable.ic_help_outline_black_24dp, 0.54f);
            refreshDrawable = new ThemeDrawable(R.drawable.ic_refresh_black_24dp, 0.54f);
            reorderDrawable = new ThemeDrawable(R.drawable.ic_reorder_black_24dp, 0.54f);
        } else {
            settingsDrawable = new ThemeDrawable(R.drawable.ic_settings_white_24dp, 1f);
            imageDrawable = new ThemeDrawable(R.drawable.ic_image_white_24dp, 1f);
            sendDrawable = new ThemeDrawable(R.drawable.ic_send_white_24dp, 1f);
            clearDrawable = new ThemeDrawable(R.drawable.ic_clear_white_24dp, 1f);
            backDrawable = new ThemeDrawable(R.drawable.ic_arrow_back_white_24dp, 1f);
            doneDrawable = new ThemeDrawable(R.drawable.ic_done_white_24dp, 1f);
            historyDrawable = new ThemeDrawable(R.drawable.ic_history_white_24dp, 1f);
            listAddDrawable = new ThemeDrawable(R.drawable.ic_playlist_add_white_24dp, 1f);
            helpDrawable = new ThemeDrawable(R.drawable.ic_help_outline_white_24dp, 1f);
            refreshDrawable = new ThemeDrawable(R.drawable.ic_refresh_white_24dp, 1f);
            reorderDrawable = new ThemeDrawable(R.drawable.ic_reorder_black_24dp, 1f, true);
        }
    }

    public void resolveDrawablesNight() {
        settingsDrawable = new ThemeDrawable(R.drawable.ic_settings_white_24dp, 1f);
        imageDrawable = new ThemeDrawable(R.drawable.ic_image_white_24dp, 1f);
        sendDrawable = new ThemeDrawable(R.drawable.ic_send_white_24dp, 1f);
        clearDrawable = new ThemeDrawable(R.drawable.ic_clear_white_24dp, 1f);
        backDrawable = new ThemeDrawable(R.drawable.ic_arrow_back_white_24dp, 1f);
        doneDrawable = new ThemeDrawable(R.drawable.ic_done_white_24dp, 1f);
        historyDrawable = new ThemeDrawable(R.drawable.ic_history_white_24dp, 1f);
        listAddDrawable = new ThemeDrawable(R.drawable.ic_playlist_add_white_24dp, 1f);
        helpDrawable = new ThemeDrawable(R.drawable.ic_help_outline_white_24dp, 1f);
        refreshDrawable = new ThemeDrawable(R.drawable.ic_refresh_white_24dp, 1f);
        reorderDrawable = new ThemeDrawable(R.drawable.ic_reorder_black_24dp, 1f, true);
    }

    public void applyFabColor(FloatingActionButton fab) {
        fab.setBackgroundTintList(ColorStateList.valueOf(accentColor.color));
    }

    public void resolveSpanColors() {
        resolveSpanColors(resValue);
    }

    @SuppressWarnings("ResourceType")
    public void resolveSpanColors(int styleRes) {
        Resources.Theme theme = AndroidUtils.getAppContext().getResources().newTheme();
        theme.applyStyle(R.style.Chan_Theme, true);
        theme.applyStyle(styleRes, true);

        TypedArray ta = theme.obtainStyledAttributes(new int[]{
                R.attr.post_quote_color,
                R.attr.post_highlight_quote_color,
                R.attr.post_link_color,
                R.attr.post_spoiler_color,
                R.attr.post_inline_quote_color,
                R.attr.post_subject_color,
                R.attr.post_name_color,
                R.attr.post_id_background_light,
                R.attr.post_id_background_dark,
                R.attr.post_capcode_color,
                R.attr.post_details_color,
                R.attr.post_highlighted_color,
                R.attr.post_saved_reply_color,
                R.attr.post_selected_color,
                R.attr.text_color_primary,
                R.attr.text_color_secondary,
                R.attr.text_color_hint,
                R.attr.text_color_reveal_spoiler,
                R.attr.colorPrimary,
                R.attr.colorAccent,
                R.attr.loading_bar_color,
                R.attr.backcolor,
                R.attr.backcolor_secondary,
                R.attr.divider_color,
                R.attr.divider_split_color,
                R.attr.ui_text_color
        });

        quoteColor = ta.getColor(0, 0);
        highlightQuoteColor = ta.getColor(1, 0);
        linkColor = ta.getColor(2, 0);
        spoilerColor = ta.getColor(3, 0);
        inlineQuoteColor = ta.getColor(4, 0);
        subjectColor = ta.getColor(5, 0);
        nameColor = ta.getColor(6, 0);
        idBackgroundLight = ta.getColor(7, 0);
        idBackgroundDark = ta.getColor(8, 0);
        capcodeColor = ta.getColor(9, 0);
        detailsColor = ta.getColor(10, 0);
        highlightedColor = ta.getColor(11, 0);
        savedReplyColor = ta.getColor(12, 0);
        selectedColor = ta.getColor(13, 0);
        textPrimary = ta.getColor(14, 0);
        textSecondary = ta.getColor(15, 0);
        textHint = ta.getColor(16, 0);
        textColorRevealSpoiler = ta.getColor(17, 0);
        int primaryColorInt = ta.getColor(18, 0);
        int accentColorInt = ta.getColor(19, 0);
        int loadingBarColorInt = ta.getColor(20, 0);
        backColor = ta.getColor(21, 0);
        backColorSecondary = ta.getColor(22, 0);
        dividerColor = ta.getColor(23, 0);
        dividerSplitColor = ta.getColor(24, 0);
        uiTextColor = ta.getColor(25, 0);

        ta.recycle();

        if (primaryColorInt != 0) {
            primaryColor = resolvePrimaryColor(primaryColorInt, primaryColor);
        }

        if (accentColorInt != 0) {
            accentColor = resolvePrimaryColor(accentColorInt, ThemeHelper.PrimaryColor.BLUE);
        }

        if (loadingBarColorInt != 0) {
            loadingBarColor = resolvePrimaryColor(loadingBarColorInt, primaryColor);
        } else {
            loadingBarColor = primaryColor;
        }
        
        // Apply overrides
        for (Map.Entry<String, Integer> entry : colorOverrides.entrySet()) {
            applyColorOverride(entry.getKey(), entry.getValue());
        }
    }

    public int getColorForAttr(int attr) {
        if (attr == R.attr.backcolor) return backColor;
        if (attr == R.attr.backcolor_secondary) return backColorSecondary;
        if (attr == R.attr.text_color_primary) return textPrimary;
        if (attr == R.attr.text_color_secondary) return textSecondary;
        if (attr == R.attr.text_color_hint) return textHint;
        if (attr == R.attr.post_quote_color) return quoteColor;
        if (attr == R.attr.post_highlight_quote_color) return highlightQuoteColor;
        if (attr == R.attr.post_link_color) return linkColor;
        if (attr == R.attr.post_subject_color) return subjectColor;
        if (attr == R.attr.post_name_color) return nameColor;
        if (attr == R.attr.colorPrimary) return primaryColor.color;
        if (attr == R.attr.colorAccent) return accentColor.color;
        if (attr == R.attr.loading_bar_color) return loadingBarColor.color;
        if (attr == R.attr.divider_color) return dividerColor;
        if (attr == R.attr.ui_text_color) return uiTextColor;
        return 0;
    }

    public void applyColorOverride(String attrName, int color) {
        if ("post_quote_color".equals(attrName)) quoteColor = color;
        else if ("post_highlight_quote_color".equals(attrName)) highlightQuoteColor = color;
        else if ("post_link_color".equals(attrName)) linkColor = color;
        else if ("post_spoiler_color".equals(attrName)) spoilerColor = color;
        else if ("post_inline_quote_color".equals(attrName)) inlineQuoteColor = color;
        else if ("post_subject_color".equals(attrName)) subjectColor = color;
        else if ("post_name_color".equals(attrName)) nameColor = color;
        else if ("post_id_background_light".equals(attrName)) idBackgroundLight = color;
        else if ("post_id_background_dark".equals(attrName)) idBackgroundDark = color;
        else if ("post_capcode_color".equals(attrName)) capcodeColor = color;
        else if ("post_details_color".equals(attrName)) detailsColor = color;
        else if ("post_highlighted_color".equals(attrName)) highlightedColor = color;
        else if ("post_saved_reply_color".equals(attrName)) savedReplyColor = color;
        else if ("post_selected_color".equals(attrName)) selectedColor = color;
        else if ("text_color_primary".equals(attrName)) textPrimary = color;
        else if ("text_color_secondary".equals(attrName)) textSecondary = color;
        else if ("text_color_hint".equals(attrName)) textHint = color;
        else if ("text_color_reveal_spoiler".equals(attrName)) textColorRevealSpoiler = color;
        else if ("colorPrimary".equals(attrName)) {
            primaryColor = resolvePrimaryColor(color, primaryColor);
        }
        else if ("colorAccent".equals(attrName)) {
            accentColor = resolvePrimaryColor(color, ThemeHelper.PrimaryColor.BLUE);
        }
        else if ("loading_bar_color".equals(attrName)) {
            loadingBarColor = resolvePrimaryColor(color, primaryColor);
        }
        else if ("backcolor".equals(attrName)) {
            backColor = color;
            backColorSecondary = color;
        }
        else if ("backcolor_secondary".equals(attrName)) backColorSecondary = color;
        else if ("divider_color".equals(attrName)) dividerColor = color;
        else if ("divider_split_color".equals(attrName)) dividerSplitColor = color;
        else if ("ui_text_color".equals(attrName)) uiTextColor = color;
    }

    private ThemeHelper.PrimaryColor resolvePrimaryColor(int color, ThemeHelper.PrimaryColor defaultColor) {
        for (ThemeHelper.PrimaryColor pc : ThemeHelper.PrimaryColor.values()) {
            if (pc.color == color) {
                return pc;
            }
        }
        String hex = String.format("#%08X", color);
        return ThemeHelper.PrimaryColor.fromHex("Custom", hex, defaultColor);
    }

    public class ThemeDrawable {
        public int drawable;
        public float alpha;
        public int intAlpha;
        public boolean tint;

        public ThemeDrawable(int drawable, float alpha) {
            this(drawable, alpha, false);
        }

        public ThemeDrawable(int drawable, float alpha, boolean tint) {
            this.drawable = drawable;
            this.alpha = alpha;
            this.tint = tint;
            intAlpha = Math.round(alpha * 0xff);
        }

        public void apply(ImageView imageView) {
            Drawable d = makeDrawable(imageView.getContext());
            imageView.setImageDrawable(d);
        }

        public Drawable makeDrawable(Context context) {
            Drawable d = ContextCompat.getDrawable(context, drawable);
            if (d != null) {
                d = d.mutate();
                d.setAlpha(intAlpha);
                if (tint) {
                    DrawableCompat.setTint(d, textSecondary);
                }
            }
            return d;
        }
    }
}
