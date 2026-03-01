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
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
import org.otacoo.chan.ui.activity.BoardActivity;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.ui.cell.PostCell;
import org.otacoo.chan.ui.dialog.ColorPickerView;
import org.otacoo.chan.ui.theme.Theme;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.ui.toolbar.NavigationItem;
import org.otacoo.chan.ui.toolbar.Toolbar;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.ui.view.ViewPagerAdapter;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Time;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private FloatingActionButton reset;
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
        navigation.buildMenu()
                .withItem(R.id.menu_add_theme, R.drawable.ic_add_white_24dp, item -> {
                    showCreateThemeDialog(null);
                })
                .build();

        view = inflateRes(R.layout.controller_theme);

        themeHelper = ThemeHelper.getInstance();
        themes = new ArrayList<>(themeHelper.getThemes());

        pager = view.findViewById(R.id.pager);
        done = view.findViewById(R.id.add);
        done.setOnClickListener(this);

        reset = view.findViewById(R.id.reset);
        reset.setOnClickListener(this);

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

        // Update colors when switching theme tabs
        pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageSelected(int position) {
                // Reset accent and loading bar colors to the selected theme's defaults
                Theme selectedTheme = themes.get(position);
                selectedAccentColor = selectedTheme.defaultAccentColor;
                selectedLoadingBarColor = selectedTheme.defaultLoadingBarColor;
                done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
                reset.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
            }

            @Override
            public void onPageScrollStateChanged(int state) {}
        });

        int targetItem = -1;
        for (int i = 0; i < themes.size(); i++) {
            Theme theme = themes.get(i);
            selectedPrimaryColors.add(theme.primaryColor);

            if (theme.name.equals(currentSettingsTheme.theme)) {
                targetItem = i;
            }
        }

        if (targetItem != -1) {
            pager.setCurrentItem(targetItem, false);
        }

        selectedAccentColor = themeHelper.getTheme().accentColor;
        selectedLoadingBarColor = themeHelper.getTheme().loadingBarColor;

        done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
        reset.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
    }

    @Override
    public void onClick(View v) {
        if (v == done) {
            saveTheme();
        } else if (v == reset) {
            resetTheme();
        }
    }

    private void saveTheme() {
        int currentItem = pager.getCurrentItem();
        Theme selectedTheme = themes.get(currentItem);
        ThemeHelper.PrimaryColor selectedColor = selectedPrimaryColors.get(currentItem);
        themeHelper.changeTheme(selectedTheme, selectedColor, selectedAccentColor, selectedLoadingBarColor);
        
        // Restart the app to apply the theme
        ((StartActivity) context).restart();
    }

    private void resetTheme() {
        int position = pager.getCurrentItem();
        Theme theme = themes.get(position);

        selectedPrimaryColors.set(position, theme.defaultPrimaryColor);
        selectedAccentColor = theme.defaultAccentColor;
        selectedLoadingBarColor = theme.defaultLoadingBarColor;

        done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
        reset.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));

        adapter.notifyDataSetChanged();

        Toast.makeText(context, R.string.setting_theme_reset_done, Toast.LENGTH_SHORT).show();
    }

    private interface ColorCallback {
        void onColorSelected(ThemeHelper.PrimaryColor color);
    }

    private void showColorPickerDialog(String title, ThemeHelper.PrimaryColor initialColor, ColorCallback callback) {
        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        TextView predefinedText = new TextView(context);
        predefinedText.setText("Choose a preset color:");
        predefinedText.setPadding(0, 0, 0, dp(8));
        root.addView(predefinedText);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(5);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);

        final ThemeHelper.PrimaryColor[] currentSelection = {initialColor};
        final List<View> squareViews = new ArrayList<>();

        List<ThemeHelper.PrimaryColor> colors = themeHelper.getColors();
        
        ColorPickerView picker = new ColorPickerView(context);
        picker.setColor(initialColor.color);
        LinearLayout.LayoutParams pickerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        picker.setLayoutParams(pickerLp);

        for (ThemeHelper.PrimaryColor color : colors) {
            View colorView = new View(context);
            int size = dp(40);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            colorView.setLayoutParams(lp);
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(color.color500);
            shape.setCornerRadius(dp(4));
            
            boolean isSelected = initialColor != null && color.name.equals(initialColor.name);
            if (isSelected) {
                shape.setStroke(dp(2), Color.BLACK);
            }
            
            colorView.setBackground(shape);
            
            colorView.setOnClickListener(v -> {
                currentSelection[0] = color;
                picker.setColor(color.color);
                for (int i = 0; i < colors.size(); i++) {
                    GradientDrawable s = (GradientDrawable) squareViews.get(i).getBackground();
                    if (colors.get(i).name.equals(color.name)) {
                        s.setStroke(dp(2), Color.BLACK);
                    } else {
                        s.setStroke(0, 0);
                    }
                }
            });
            
            squareViews.add(colorView);
            grid.addView(colorView);
        }

        root.addView(grid);

        TextView customText = new TextView(context);
        customText.setText("\nOr pick a custom color:");
        customText.setPadding(0, dp(8), 0, dp(8));
        root.addView(customText);

        picker.setOnColorChangedListener(color -> {
            // User touched the picker, clear preset highlight
            currentSelection[0] = null;
            for (View v : squareViews) {
                ((GradientDrawable) v.getBackground()).setStroke(0, 0);
            }
        });
        
        root.addView(picker);

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(R.string.ok, (dlg, which) -> {
                    if (currentSelection[0] != null) {
                        callback.onColorSelected(currentSelection[0]);
                    } else {
                        int finalColor = picker.getColor();
                        callback.onColorSelected(ThemeHelper.PrimaryColor.fromHex("Custom", String.format("#%08X", finalColor), initialColor));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAccentColorPicker() {
        showColorPickerDialog("Change the FAB color", selectedAccentColor, color -> {
            selectedAccentColor = color;
            done.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
            reset.setBackgroundTintList(ColorStateList.valueOf(selectedAccentColor.color));
        });
    }

    private void showLoadingBarColorPicker() {
        showColorPickerDialog("Change Loading bar color", selectedLoadingBarColor, color -> {
            selectedLoadingBarColor = color;
            adapter.notifyDataSetChanged();
        });
    }

    private void showCreateThemeDialog(final ChanSettings.CustomTheme existing) {
        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        EditText nameEdit = new EditText(context);
        nameEdit.setHint(R.string.setting_theme_create_name);
        if (existing != null) nameEdit.setText(existing.displayName);
        root.addView(nameEdit);

        TextView baseLabel = new TextView(context);
        baseLabel.setText(R.string.setting_theme_create_base);
        baseLabel.setPadding(0, dp(8), 0, 0);
        root.addView(baseLabel);

        Spinner baseSpinner = new Spinner(context);
        String[] bases = {"Light", "Dark"};
        ArrayAdapter<String> baseAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, bases);
        baseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baseSpinner.setAdapter(baseAdapter);
        if (existing != null) baseSpinner.setSelection("dark".equals(existing.baseTheme) ? 1 : 0);
        root.addView(baseSpinner);

        Map<String, Integer> existingOverrides = sanitizeColorOverrides(existing != null ? existing.colorOverrides : null);
        if (existing != null) {
            existing.colorOverrides = existingOverrides;
        }
        final Map<String, Integer> overrides = new HashMap<>(existingOverrides);
        
        String[] attrNames = {
                "colorPrimary",
                "colorAccent",
                "backcolor",
                "loading_bar_color",
                "text_color_primary",
                "post_quote_color",
                "post_link_color",
                "post_subject_color",
                "post_name_color"
        };
        int[] names = {
                R.string.setting_theme_item_toolbar_color,
                R.string.setting_theme_item_fab_color,
                R.string.setting_theme_item_backcolor,
                R.string.setting_theme_item_loading_bar_color,
                R.string.setting_theme_item_text_color_primary,
                R.string.setting_theme_item_post_quote_color,
                R.string.setting_theme_item_post_link_color,
                R.string.setting_theme_item_post_subject_color,
                R.string.setting_theme_item_post_name_color
        };

        for (int i = 0; i < attrNames.length; i++) {
            final String attrName = attrNames[i];
            final int nameRes = names[i];
            
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            
            TextView label = new TextView(context);
            label.setText(nameRes);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            
            View colorPreview = new View(context);
            int size = dp(32);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            colorPreview.setLayoutParams(lp);
            
            // Only use override if it exists; otherwise use a preview color (gray for new themes)
            int colorVal;
            if (existing != null) {
                colorVal = getOverrideColor(overrides, attrName, Color.GRAY);
            } else {
                // For new themes, show gray as placeholder but don't add to overrides yet
                // When user explicitly picks a color, it will be added to overrides
                colorVal = Color.GRAY;
            }
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(colorVal);
            shape.setStroke(dp(1), Color.DKGRAY);
            colorPreview.setBackground(shape);
            
            colorPreview.setOnClickListener(v -> {
                int currentColor = getOverrideColor(overrides, attrName, ThemeHelper.PrimaryColor.GREY.color);
                showColorPickerDialog(getString(nameRes), 
                    themeHelper.getColor(String.format("#%08X", currentColor), ThemeHelper.PrimaryColor.GREY), 
                    color -> {
                        overrides.put(attrName, color.color);
                        shape.setColor(color.color);
                    });
            });
            
            row.addView(colorPreview);
            root.addView(row);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(existing != null ? R.string.settings_screen_theme : R.string.setting_theme_add)
                .setView(scrollView)
                .setPositiveButton(existing != null ? R.string.save : R.string.add, (dlg, which) -> {
                    String name = nameEdit.getText().toString();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(context, "Please enter a name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    boolean isLight = baseSpinner.getSelectedItemPosition() == 0;
                    String baseTheme = isLight ? "light" : "dark";
                    String themeId = existing != null ? existing.name : "custom_" + System.currentTimeMillis();
                    
                    ChanSettings.CustomTheme custom = new ChanSettings.CustomTheme(name, themeId, baseTheme, isLight, overrides);
                    themeHelper.addCustomTheme(custom);
                    
                    themes = new ArrayList<>(themeHelper.getThemes());
                    if (existing == null) {
                        Theme newTheme = themes.get(themes.size() - 1);
                        selectedPrimaryColors.add(newTheme.primaryColor);
                    } else {
                        int index = pager.getCurrentItem();
                        selectedPrimaryColors.set(index, themes.get(index).primaryColor);
                    }

                    adapter.notifyDataSetChanged();

                    if (existing == null) {
                        pager.setCurrentItem(themes.size() - 1, true);
                    }
                })
                .setNegativeButton(R.string.cancel, null);
        
        if (existing != null) {
            builder.setNeutralButton(R.string.remove, (dlg, which) -> {
                themeHelper.removeCustomTheme(existing.name);
                themes = new ArrayList<>(themeHelper.getThemes());
                selectedPrimaryColors.clear();
                for (Theme t : themes) selectedPrimaryColors.add(t.primaryColor);
                
                adapter.notifyDataSetChanged();
                
                ChanSettings.ThemeColor current = ChanSettings.getThemeAndColor();
                for (int i = 0; i < themes.size(); i++) {
                    if (themes.get(i).name.equals(current.theme)) {
                        pager.setCurrentItem(i, false);
                        break;
                    }
                }

                Toast.makeText(context, "Theme removed", Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    private Map<String, Integer> sanitizeColorOverrides(Map<String, Integer> overrides) {
        Map<String, Integer> sanitized = new HashMap<>();
        if (overrides == null) {
            return sanitized;
        }

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) overrides).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof Number) {
                sanitized.put((String) entry.getKey(), ((Number) value).intValue());
            } else if (value instanceof String) {
                try {
                    sanitized.put((String) entry.getKey(), (int) Long.parseLong((String) value));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return sanitized;
    }

    private int getOverrideColor(Map<String, Integer> overrides, String attrName, int fallback) {
        if (overrides == null) {
            return fallback;
        }
        Integer color = overrides.get(attrName);
        return color != null ? color : fallback;
    }

    private class Adapter extends ViewPagerAdapter {
        public Adapter() {
        }

        @Override
        public int getItemPosition(Object object) {
            return POSITION_NONE;
        }

        @Override
        public View getView(final int position, ViewGroup parent) {
            final Theme theme = themes.get(position);

            Context themeContext = new ContextThemeWrapper(context, theme.resValue);

            LinearLayout linearLayout = new LinearLayout(themeContext);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setBackgroundColor(theme.backColor);

            final Toolbar toolbar = new Toolbar(themeContext);
            
            final View.OnClickListener colorClick = v -> {
                if (theme.name.equals("auto")) return; // Disable for Auto theme

                if (theme.name.startsWith("custom_")) {
                    showCreateThemeDialog(themeHelper.getCustomTheme(theme.name));
                } else {
                    showColorPickerDialog("Change Toolbar color", selectedPrimaryColors.get(position), color -> {
                        selectedPrimaryColors.set(position, color);
                        theme.primaryColor = color;
                        toolbar.setBackgroundColor(color.color);
                    });
                }
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

            // Add Loading Bar Preview
            View loadingBar = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
            loadingBar.setLayoutParams(lp);
            
            ColorDrawable colorDrawable = new ColorDrawable(theme.loadingBarColor.color);
            ClipDrawable clipDrawable = new ClipDrawable(colorDrawable, Gravity.START, ClipDrawable.HORIZONTAL);
            clipDrawable.setLevel(5000); // 50% progress
            loadingBar.setBackground(clipDrawable);
            
            linearLayout.addView(loadingBar);

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
            side.setBackgroundColor(theme.backColor);
            side.addView(createPreviewCell(themeContext, theme), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return side;
        }

        @Override
        public int getCount() {
            return themes.size();
        }
    }
}
