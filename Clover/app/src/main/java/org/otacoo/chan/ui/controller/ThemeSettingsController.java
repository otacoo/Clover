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

import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.model.PostLinkable;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.common.DefaultPostParser;
import org.otacoo.chan.core.site.parser.CommentParser;
import org.otacoo.chan.core.site.parser.PostParser;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.ui.cell.PostCell;
import org.otacoo.chan.ui.theme.Theme;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.ui.toolbar.NavigationItem;
import org.otacoo.chan.ui.toolbar.Toolbar;
import org.otacoo.chan.ui.view.FloatingMenu;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.ui.view.ViewPagerAdapter;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Time;

import java.util.ArrayList;
import java.util.List;

public class ThemeSettingsController extends Controller implements View.OnClickListener {
    private Board dummyBoard;

    {
        dummyBoard = new Board();
        dummyBoard.name = "name";
        dummyBoard.code = "code";
    }

    private Loadable dummyLoadable;

    {
        dummyLoadable = Loadable.emptyLoadable();
        dummyLoadable.mode = Loadable.Mode.THREAD;
    }

    private PostCell.PostCellCallback dummyPostCallback = new PostCell.PostCellCallback() {
        @Override
        public Loadable getLoadable() {
            return dummyLoadable;
        }

        @Override
        public void onPostClicked(Post post) {
        }

        @Override
        public void onThumbnailClicked(Post post, PostImage postImage, ThumbnailView thumbnail) {
        }

        @Override
        public void onShowPostReplies(Post post) {
        }

        @Override
        public Object onPopulatePostOptions(Post post, List<FloatingMenuItem> menu, List<FloatingMenuItem> extraMenu) {
            menu.add(new FloatingMenuItem(1, "Option"));
            return 0;
        }

        @Override
        public void onPostOptionClicked(Post post, Object id) {
        }

        @Override
        public void onPostLinkableClicked(Post post, PostLinkable linkable) {
        }

        @Override
        public void onPostNoClicked(Post post) {
        }

        @Override
        public void onPostSelectionQuoted(Post post, CharSequence quoted) {
        }
    };

    private PostParser.Callback parserCallback = new PostParser.Callback() {
        @Override
        public boolean isSaved(int postNo) {
            return false;
        }

        @Override
        public boolean isInternal(int postNo) {
            return false;
        }
    };

    private ViewPager pager;
    private FloatingActionButton done;
    private TextView textView;

    private Adapter adapter;
    private ThemeHelper themeHelper;

    private List<Theme> themes;
    private List<ThemeHelper.PrimaryColor> selectedPrimaryColors = new ArrayList<>();
    private ThemeHelper.PrimaryColor selectedAccentColor;
    private ThemeHelper.PrimaryColor selectedLoadingBarColor;

    public ThemeSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.setTitle(R.string.settings_screen_theme);
        navigation.swipeable = false;
        view = inflateRes(R.layout.controller_theme);

        themeHelper = ThemeHelper.getInstance();
        themes = themeHelper.getThemes();

        pager = view.findViewById(R.id.pager);
        done = view.findViewById(R.id.add);
        done.setOnClickListener(this);

        textView = view.findViewById(R.id.text);

        ChanSettings.ThemeColor currentSettingsTheme = ChanSettings.getThemeAndColor();

        SpannableString changeAccentColor = new SpannableString("\n" + getString(R.string.setting_theme_accent));
        changeAccentColor.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                showAccentColorPicker();
            }
        }, 1, changeAccentColor.length(), 0);

        SpannableString changeLoadingBarColor = new SpannableString("\nTap here to change the Loading bar color");
        changeLoadingBarColor.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                showLoadingBarColorPicker();
            }
        }, 1, changeLoadingBarColor.length(), 0);

        textView.setText(TextUtils.concat(
                getString(R.string.setting_theme_explanation),
                changeAccentColor,
                changeLoadingBarColor
        ));
        textView.setMovementMethod(LinkMovementMethod.getInstance());

        adapter = new Adapter();
        pager.setAdapter(adapter);

        for (int i = 0; i < themeHelper.getThemes().size(); i++) {
            Theme theme = themeHelper.getThemes().get(i);
            ThemeHelper.PrimaryColor primaryColor = theme.primaryColor;

            if (theme.name.equals(currentSettingsTheme.theme)) {
                // Current theme
                pager.setCurrentItem(i, false);
            }
            selectedPrimaryColors.add(primaryColor);
        }

        selectedAccentColor = themeHelper.getTheme().accentColor;
        selectedLoadingBarColor = themeHelper.getTheme().loadingBarColor;

        done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
    }

    @Override
    public void onClick(View v) {
        if (v == done) {
            saveTheme();
        }
    }

    private void saveTheme() {
        int currentItem = pager.getCurrentItem();
        Theme selectedTheme = themeHelper.getThemes().get(currentItem);
        ThemeHelper.PrimaryColor selectedColor = selectedPrimaryColors.get(currentItem);
        themeHelper.changeTheme(selectedTheme, selectedColor, selectedAccentColor, selectedLoadingBarColor);
        ((StartActivity) context).restart();
    }

    private void showAccentColorPicker() {
        List<FloatingMenuItem> items = new ArrayList<>();
        FloatingMenuItem selected = null;
        for (ThemeHelper.PrimaryColor color : themeHelper.getColors()) {
            FloatingMenuItem floatingMenuItem = new FloatingMenuItem(new ColorsAdapterItem(color, color.color500), color.displayName);
            items.add(floatingMenuItem);
            if (color.name.equals(selectedAccentColor.name)) {
                selected = floatingMenuItem;
            }
        }

        FloatingMenu menu = getColorsMenu(items, selected, textView);
        menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
            @Override
            public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                ColorsAdapterItem colorItem = (ColorsAdapterItem) item.getId();
                selectedAccentColor = colorItem.color;
                done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
            }

            @Override
            public void onFloatingMenuDismissed(FloatingMenu menu) {
            }
        });
        menu.show();
    }

    private void showLoadingBarColorPicker() {
        List<FloatingMenuItem> items = new ArrayList<>();
        FloatingMenuItem selected = null;
        for (ThemeHelper.PrimaryColor color : themeHelper.getColors()) {
            FloatingMenuItem floatingMenuItem = new FloatingMenuItem(new ColorsAdapterItem(color, color.color500), color.displayName);
            items.add(floatingMenuItem);
            if (color.name.equals(selectedLoadingBarColor.name)) {
                selected = floatingMenuItem;
            }
        }

        FloatingMenu menu = getColorsMenu(items, selected, textView);
        menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
            @Override
            public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                ColorsAdapterItem colorItem = (ColorsAdapterItem) item.getId();
                selectedLoadingBarColor = colorItem.color;
            }

            @Override
            public void onFloatingMenuDismissed(FloatingMenu menu) {
            }
        });
        menu.show();
    }

    private FloatingMenu getColorsMenu(List<FloatingMenuItem> items, FloatingMenuItem selected, View anchor) {
        FloatingMenu menu = new FloatingMenu(context);

        menu.setItems(items);
        menu.setAdapter(new ColorsAdapter(items));
        menu.setSelectedItem(selected);
        menu.setAnchor(anchor, Gravity.CENTER, 0, dp(5));
        menu.setPopupWidth(anchor.getWidth());
        return menu;
    }

    private class Adapter extends ViewPagerAdapter {
        public Adapter() {
        }

        @Override
        public View getView(final int position, ViewGroup parent) {
            final Theme theme = themes.get(position);

            Context themeContext = new ContextThemeWrapper(context, theme.resValue);

            LinearLayout linearLayout = new LinearLayout(themeContext);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setBackgroundColor(getAttrColor(themeContext, R.attr.backcolor));

            final Toolbar toolbar = new Toolbar(themeContext);
            
            final View.OnClickListener colorClick = v -> {
                if (theme.name.equals("auto")) return; // Disable for Auto theme

                List<FloatingMenuItem> items = new ArrayList<>();
                FloatingMenuItem selected = null;
                for (ThemeHelper.PrimaryColor color : themeHelper.getColors()) {
                    FloatingMenuItem floatingMenuItem = new FloatingMenuItem(new ColorsAdapterItem(color, color.color500), color.displayName);
                    items.add(floatingMenuItem);
                    if (color == selectedPrimaryColors.get(position)) {
                        selected = floatingMenuItem;
                    }
                }

                FloatingMenu menu = getColorsMenu(items, selected, toolbar);
                menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
                    @Override
                    public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                        ColorsAdapterItem colorItem = (ColorsAdapterItem) item.getId();
                        selectedPrimaryColors.set(position, colorItem.color);
                        toolbar.setBackgroundColor(colorItem.color.color);
                    }

                    @Override
                    public void onFloatingMenuDismissed(FloatingMenu menu) {
                    }
                });
                menu.show();
            };
            toolbar.setCallback(new Toolbar.ToolbarCallback() {
                @Override
                public void onMenuOrBackClicked(boolean isArrow) {
                    colorClick.onClick(toolbar);
                }

                @Override
                public void onSearchVisibilityChanged(NavigationItem item, boolean visible) {
                }

                @Override
                public void onSearchEntered(NavigationItem item, String entered) {
                }
            });
            
            if (theme.name.equals("auto")) {
                Theme lightTheme = null;
                Theme darkTheme = null;
                for (Theme t : themes) {
                    if (t.name.equals("light")) lightTheme = t;
                    if (t.name.equals("dark")) darkTheme = t;
                }
                
                if (lightTheme != null && darkTheme != null) {
                    int lightColor = lightTheme.primaryColor.color;
                    int darkColor = darkTheme.primaryColor.color;
                    
                    GradientDrawable gradient = new GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            new int[]{lightColor, lightColor, darkColor, darkColor}
                    );
                    toolbar.setBackground(gradient);
                } else {
                    toolbar.setBackgroundColor(selectedPrimaryColors.get(position).color);
                }
            } else {
                toolbar.setBackgroundColor(selectedPrimaryColors.get(position).color);
                toolbar.setOnClickListener(colorClick);
            }
            
            final NavigationItem item = new NavigationItem();
            item.title = theme.displayName;
            item.hasBack = false;
            toolbar.setNavigationItem(false, true, item);

            linearLayout.addView(toolbar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    themeContext.getResources().getDimensionPixelSize(R.dimen.toolbar_height)));

            if (theme.name.equals("auto")) {
                LinearLayout split = new LinearLayout(context);
                split.setOrientation(LinearLayout.HORIZONTAL);

                Theme lightTheme = null;
                Theme darkTheme = null;
                for (Theme t : themes) {
                    if (t.name.equals("light")) lightTheme = t;
                    if (t.name.equals("dark")) darkTheme = t;
                }

                if (lightTheme != null) {
                    split.addView(createPreviewSide(lightTheme), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
                if (darkTheme != null) {
                    split.addView(createPreviewSide(darkTheme), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }

                linearLayout.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                linearLayout.addView(createPreviewCell(themeContext, theme), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            return linearLayout;
        }

        private Post.Builder createDummyPostBuilder() {
            return new Post.Builder()
                    .board(dummyBoard)
                    .id(123456789)
                    .opId(1)
                    .setUnixTimestampSeconds((Time.get() - (30 * 60 * 1000)) / 1000)
                    .subject("Lorem ipsum")
                    .comment("<a href=\"#p123456789\" class=\"quotelink\">&gt;&gt;123456789</a><br>" +
                            "Lorem ipsum dolor sit amet, consectetur adipiscing elit.<br>" +
                            "<br>" +
                            "<a href=\"#p123456789\" class=\"quotelink\">&gt;&gt;123456789</a><br>" +
                            "http://example.com/" +
                            "<br>" +
                            "Phasellus consequat semper sodales. Donec dolor lectus, aliquet nec mollis vel, rutrum vel enim.");
        }

        private View createPreviewCell(Context themeContext, Theme theme) {
            Post post = new DefaultPostParser(new CommentParser()).parse(theme, createDummyPostBuilder(), parserCallback);
            PostCell postCell = (PostCell) LayoutInflater.from(themeContext).inflate(R.layout.cell_post, null);
            postCell.setPost(theme,
                    post,
                    dummyPostCallback,
                    false,
                    false,
                    false,
                    -1,
                    true,
                    ChanSettings.PostViewMode.LIST,
                    false);
            return postCell;
        }

        private View createPreviewSide(Theme theme) {
            Context themeContext = new ContextThemeWrapper(context, theme.resValue);
            LinearLayout side = new LinearLayout(themeContext);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setBackgroundColor(getAttrColor(themeContext, R.attr.backcolor));
            side.addView(createPreviewCell(themeContext, theme), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return side;
        }

        @Override
        public int getCount() {
            return themes.size();
        }
    }

    private class ColorsAdapter extends BaseAdapter {
        private List<FloatingMenuItem> items;

        public ColorsAdapter(List<FloatingMenuItem> items) {
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            @SuppressLint("ViewHolder")
            TextView textView = (TextView) LayoutInflater.from(context).inflate(R.layout.toolbar_menu_item, parent, false);
            textView.setText(getItem(position));
            textView.setTypeface(AndroidUtils.ROBOTO_MEDIUM);

            ColorsAdapterItem color = (ColorsAdapterItem) items.get(position).getId();

            textView.setBackgroundColor(color.bg);
            boolean lightColor = (Color.red(color.bg) * 0.299f) + (Color.green(color.bg) * 0.587f) + (Color.blue(color.bg) * 0.114f) > 125f;
            textView.setTextColor(lightColor ? 0xff000000 : 0xffffffff);

            return textView;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public String getItem(int position) {
            return items.get(position).getText();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }

    private static class ColorsAdapterItem {
        public ThemeHelper.PrimaryColor color;
        public int bg;

        public ColorsAdapterItem(ThemeHelper.PrimaryColor color, int bg) {
            this.color = color;
            this.bg = bg;
        }
    }
}
