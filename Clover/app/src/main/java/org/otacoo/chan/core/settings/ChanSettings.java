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
package org.otacoo.chan.core.settings;

import android.text.TextUtils;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import org.otacoo.chan.R;
import org.otacoo.chan.core.manager.WatchManager;
import org.otacoo.chan.core.update.UpdateManager;
import org.otacoo.chan.ui.adapter.PostsFilter;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.greenrobot.event.EventBus;

public class ChanSettings {
    public enum MediaAutoLoadMode implements OptionSettingItem {
        // ALways auto load, either wifi or mobile
        ALL("all"),
        // Only auto load if on wifi
        WIFI("wifi"),
        // Never auto load
        NONE("none");

        String name;

        MediaAutoLoadMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }

        public boolean shouldLoad() {
            if (this == NONE) {
                return false;
            } else if (this == WIFI) {
                return org.otacoo.chan.utils.AndroidUtils.isConnected(1);
            } else if (this == ALL) {
                return true;
            }
            return false;
        }
    }

    public enum PostViewMode implements OptionSettingItem {
        LIST("list"),
        CARD("grid");

        String name;

        PostViewMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }
    }

    public enum LayoutMode implements OptionSettingItem {
        AUTO("auto"),
        PHONE("phone"),
        SLIDE("slide"),
        SPLIT("split");

        String name;

        LayoutMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }
    }

    public enum DestinationFolderMode implements OptionSettingItem {
        ROOT("root", 0x0),
        SITE("site", DestinationFolderMode.site),
        SITE_BOARD("siteboard", DestinationFolderMode.site | DestinationFolderMode.board),
        SITE_BOARD_THREAD("siteboardthread", DestinationFolderMode.site | DestinationFolderMode.board | DestinationFolderMode.thread),
        BOARD("board", DestinationFolderMode.board),
        BOARD_THREAD("boardthread", DestinationFolderMode.board | DestinationFolderMode.thread),
        LEGACY("legacy", DestinationFolderMode.thread);

        public static final int site = 0x01;
        public static final int board = 0x02;
        public static final int thread = 0x04;

        String name;
        int bitmask;

        DestinationFolderMode(String name, int bitmask) {
            this.name = name;
            this.bitmask = bitmask;
        }

        @Override
        public String getKey() {
            return name;
        }

        public int getBitmask() {
            return bitmask;
        }
    }

    public enum AppIconMode implements OptionSettingItem {
        BLUE("blue"),
        GREEN("green"),
        GOLD("gold");

        String name;

        AppIconMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }
    }

    public enum HideFlagsMode implements OptionSettingItem {
        DISABLED("disabled"),
        ALL("all"),
        CATALOG("catalog"),
        THREAD("thread");

        String name;

        HideFlagsMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }
    }

    public enum Vp9ExtensionMode implements OptionSettingItem {
        DEFAULT("default"),
        PREFER("prefer"),
        OFF("off");

        String name;

        Vp9ExtensionMode(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }
    }

    private static Proxy proxy;

    private static final StringSetting theme;
    private static final StringSetting customThemes;
    public static final StringSetting autoThemeDay;
    public static final StringSetting autoThemeNight;
    public static final OptionsSetting<LayoutMode> layoutMode;
    public static final StringSetting fontSize;
    public static final BooleanSetting fontCondensed;
    public static final IntegerSetting thumbnailScale;
    public static final BooleanSetting layoutTextBelowThumbnails;
    public static final BooleanSetting openLinkConfirmation;
    public static final BooleanSetting openLinkBrowser;
    public static final BooleanSetting autoRefreshThread;
    //    public static final BooleanSetting imageAutoLoad;
    public static final OptionsSetting<MediaAutoLoadMode> imageAutoLoadNetwork;
    public static final OptionsSetting<MediaAutoLoadMode> videoAutoLoadNetwork;
    public static final BooleanSetting videoOpenExternal;
    public static final BooleanSetting textOnly;
    public static final BooleanSetting videoErrorIgnore;
    public static final OptionsSetting<MediaAutoLoadMode> loadFullSizeThumbnails;
    public static final BooleanSetting hideAlbumImageInfo;
    public static final OptionsSetting<PostViewMode> boardViewMode;
    public static final IntegerSetting boardGridSpanCount;
    public static final StringSetting boardOrder;

    public static final StringSetting postDefaultName;
    public static final BooleanSetting postPinThread;
    public static final BooleanSetting alwaysShowReplyTags;

    public static final BooleanSetting developer;

    public static final StringSetting saveLocation;
    public static final StringSetting saveLocationTreeUri;
    public static final OptionsSetting<DestinationFolderMode> saveImageFolder;
    public static final OptionsSetting<DestinationFolderMode> saveAlbumFolder;
    public static final BooleanSetting randomizeFilename;
    public static final BooleanSetting saveOriginalFilename;
    public static final BooleanSetting saveOriginalFilenameAlbum;
    public static final BooleanSetting shareUrl;
    public static final BooleanSetting enableReplyFab;
    public static final BooleanSetting enableTopBottomFab;
    public static final BooleanSetting accessiblePostInfo;
    public static final BooleanSetting useImmersiveModeForGallery;
    public static final BooleanSetting anonymize;
    public static final BooleanSetting anonymizeIds;
    public static final OptionsSetting<HideFlagsMode> hideFlags;
    public static final BooleanSetting showAnonymousName;
    public static final BooleanSetting revealImageSpoilers;
    public static final BooleanSetting revealTextSpoilers;
    public static final BooleanSetting repliesButtonsBottom;
    public static final BooleanSetting confirmExit;
    public static final BooleanSetting disableBackButton;
    public static final BooleanSetting tapNoReply;
    public static final BooleanSetting tapQuotelinkSpan;
    public static final BooleanSetting volumeKeysScrolling;
    public static final BooleanSetting postFullDate;
    public static final BooleanSetting postFileInfo;
    public static final BooleanSetting postFilename;
    public static final BooleanSetting neverHideToolbar;
    public static final BooleanSetting toolbarBottom;
    public static final BooleanSetting controllerSwipeable;
    public static final BooleanSetting pinnedSearchesEnabled;

    public static final BooleanSetting videoDefaultMuted;
    public static final BooleanSetting videoAutoLoop;
    public static final IntegerSetting videoPlayerTimeout;
    public static final OptionsSetting<Vp9ExtensionMode> vp9ExtensionMode;
    public static final BooleanSetting videoWebViewFallback;

    public static final BooleanSetting watchEnabled;
    public static final BooleanSetting watchCountdown;
    public static final BooleanSetting highlightOpenThread;
    public static final BooleanSetting watchBackground;
    public static final IntegerSetting watchBackgroundInterval;
    public static final StringSetting watchNotifyMode;
    public static final StringSetting watchSound;
    public static final BooleanSetting watchPeek;
    public static final StringSetting watchLed;

    public static final BooleanSetting historyEnabled;

    public static final IntegerSetting previousVersion;

    public static final BooleanSetting proxyEnabled;
    public static final StringSetting proxyAddress;
    public static final IntegerSetting proxyPort;

    public static final CounterSetting historyOpenCounter;
    public static final CounterSetting threadOpenCounter;

    public static final LongSetting updateCheckTime;
    public static final LongSetting updateCheckInterval;
    public static final BooleanSetting autoCheckUpdates;

    public static final StringSetting pinnedSearches;

    public static final BooleanSetting crashReporting;
    public static final BooleanSetting reencodeHintShown;
    public static final BooleanSetting setupSitesBoardsHintShown;

    public static final BooleanSetting dnsOverHttps;

    public static final StringSetting customUserAgent;
    public static final StringSetting customCFClearanceCommand;

    public static final OptionsSetting<AppIconMode> appIconMode;

    public static final BooleanSetting enableLocalization;

    static {
        SettingProvider p = new SharedPreferencesSettingProvider(AndroidUtils.getPreferences());

        theme = new StringSetting(p, "preference_theme", "auto");
        customThemes = new StringSetting(p, "preference_custom_themes", "[]");
        autoThemeDay = new StringSetting(p, "preference_auto_theme_day", "light");
        autoThemeNight = new StringSetting(p, "preference_auto_theme_night", "dark");

        layoutMode = new OptionsSetting<>(p, "preference_layout_mode", LayoutMode.class, LayoutMode.AUTO);

        boolean tablet = AndroidUtils.getRes().getBoolean(R.bool.is_tablet);

        fontSize = new StringSetting(p, "preference_font", tablet ? "16" : "14");
        fontCondensed = new BooleanSetting(p, "preference_font_condensed", false);
        thumbnailScale = new IntegerSetting(p, "preference_thumbnail_scale", 100);
        layoutTextBelowThumbnails = new BooleanSetting(p, "layout_text_below_thumbnails", false);
        openLinkConfirmation = new BooleanSetting(p, "preference_open_link_confirmation", true);
        openLinkBrowser = new BooleanSetting(p, "preference_open_link_browser", false);
        autoRefreshThread = new BooleanSetting(p, "preference_auto_refresh_thread", true);
//        imageAutoLoad = new BooleanSetting(p, "preference_image_auto_load", true);
        imageAutoLoadNetwork = new OptionsSetting<>(p, "preference_image_auto_load_network", MediaAutoLoadMode.class, MediaAutoLoadMode.WIFI);
        videoAutoLoadNetwork = new OptionsSetting<>(p, "preference_video_auto_load_network", MediaAutoLoadMode.class, MediaAutoLoadMode.WIFI);
        videoOpenExternal = new BooleanSetting(p, "preference_video_external", false);
        textOnly = new BooleanSetting(p, "preference_text_only", false);
        videoErrorIgnore = new BooleanSetting(p, "preference_video_error_ignore", false);
        loadFullSizeThumbnails = new OptionsSetting<>(p, "preference_load_full_size_thumbnails_mode", MediaAutoLoadMode.class, MediaAutoLoadMode.NONE);
        hideAlbumImageInfo = new BooleanSetting(p, "preference_hide_album_image_info", false);
        boardViewMode = new OptionsSetting<>(p, "preference_board_view_mode", PostViewMode.class, PostViewMode.CARD);
        boardGridSpanCount = new IntegerSetting(p, "preference_board_grid_span_count", 0);
        boardOrder = new StringSetting(p, "preference_board_order", PostsFilter.Order.BUMP.name);

        postDefaultName = new StringSetting(p, "preference_default_name", "");
        postPinThread = new BooleanSetting(p, "preference_pin_on_post", true);
        alwaysShowReplyTags = new BooleanSetting(p, "preference_always_show_reply_tags", false);

        developer = new BooleanSetting(p, "preference_developer", false);

        saveLocation = new StringSetting(p, "preference_image_save_location", "");
        saveLocationTreeUri = new StringSetting(p, "preference_image_save_tree_uri", "");
        saveImageFolder = new OptionsSetting<>(p, "preference_save_image_folder", DestinationFolderMode.class, DestinationFolderMode.ROOT);
        saveAlbumFolder = new OptionsSetting<>(p, "preference_save_album_folder", DestinationFolderMode.class, DestinationFolderMode.LEGACY);
        randomizeFilename = new BooleanSetting(p, "preference_randomize_filename", false);
        saveOriginalFilename = new BooleanSetting(p, "preference_image_save_original", false);
        saveOriginalFilenameAlbum = new BooleanSetting(p, "preference_album_save_original", false);
        shareUrl = new BooleanSetting(p, "preference_image_share_url", false);
        accessiblePostInfo = new BooleanSetting(p, "preference_enable_accessible_info", false);
        useImmersiveModeForGallery = new BooleanSetting(p, "use_immersive_mode_for_gallery", false);
        enableReplyFab = new BooleanSetting(p, "preference_enable_reply_fab", true);
        enableTopBottomFab = new BooleanSetting(p, "preference_enable_top_bottom_fab", false);
        anonymize = new BooleanSetting(p, "preference_anonymize", false);
        anonymizeIds = new BooleanSetting(p, "preference_anonymize_ids", false);
        hideFlags = new OptionsSetting<>(p, "preference_hide_flags", HideFlagsMode.class, HideFlagsMode.DISABLED);
        showAnonymousName = new BooleanSetting(p, "preference_show_anonymous_name", true);
        revealImageSpoilers = new BooleanSetting(p, "preference_reveal_image_spoilers", false);
        revealTextSpoilers = new BooleanSetting(p, "preference_reveal_text_spoilers", false);
        repliesButtonsBottom = new BooleanSetting(p, "preference_buttons_bottom", false);
        confirmExit = new BooleanSetting(p, "preference_confirm_exit", true);
        disableBackButton = new BooleanSetting(p, "preference_disable_back_button", false);
        tapNoReply = new BooleanSetting(p, "preference_tap_no_reply", false);
        tapQuotelinkSpan = new BooleanSetting(p, "preference_tap_quotelink_span", true);
        volumeKeysScrolling = new BooleanSetting(p, "preference_volume_key_scrolling", false);
        postFullDate = new BooleanSetting(p, "preference_post_full_date", false);
        postFileInfo = new BooleanSetting(p, "preference_post_file_info", true);
        postFilename = new BooleanSetting(p, "preference_post_filename", true);
        neverHideToolbar = new BooleanSetting(p, "preference_never_hide_toolbar", false);
        toolbarBottom = new BooleanSetting(p, "preference_toolbar_bottom", false);
        controllerSwipeable = new BooleanSetting(p, "preference_controller_swipeable", true);
        pinnedSearchesEnabled = new BooleanSetting(p, "preference_pinned_searches_enabled", true);
        pinnedSearchesEnabled.addCallback((setting, value) ->
                EventBus.getDefault().post(new SettingChanged<>(pinnedSearchesEnabled)));
//        saveBoardFolder = new BooleanSetting(p, "preference_save_subboard", false);
        videoDefaultMuted = new BooleanSetting(p, "preference_video_default_muted", true);
        videoAutoLoop = new BooleanSetting(p, "preference_video_loop", true);
        videoPlayerTimeout = new IntegerSetting(p, "preference_video_player_timeout", 5);
        vp9ExtensionMode = new OptionsSetting<>(p, "preference_vp9_extension_mode", Vp9ExtensionMode.class, Vp9ExtensionMode.DEFAULT);
        videoWebViewFallback = new BooleanSetting(p, "preference_video_webview_fallback", true);

        watchEnabled = new BooleanSetting(p, "preference_watch_enabled", false);
        watchEnabled.addCallback((setting, value) ->
                EventBus.getDefault().post(new SettingChanged<>(watchEnabled)));
        watchCountdown = new BooleanSetting(p, "preference_watch_countdown", false);
        highlightOpenThread = new BooleanSetting(p, "preference_highlight_open_thread", false);
        highlightOpenThread.addCallback((setting, value) ->
                EventBus.getDefault().post(new SettingChanged<>(highlightOpenThread)));
        watchBackground = new BooleanSetting(p, "preference_watch_background_enabled", false);
        watchBackground.addCallback((setting, value) ->
                EventBus.getDefault().post(new SettingChanged<>(watchBackground)));
        watchBackgroundInterval = new IntegerSetting(p, "preference_watch_background_interval", WatchManager.DEFAULT_BACKGROUND_INTERVAL);
        watchNotifyMode = new StringSetting(p, "preference_watch_notify_mode", "all");
        watchSound = new StringSetting(p, "preference_watch_sound", "quotes");
        watchPeek = new BooleanSetting(p, "preference_watch_peek", true);
        watchLed = new StringSetting(p, "preference_watch_led", "ffffffff");

        historyEnabled = new BooleanSetting(p, "preference_history_enabled", true);

        previousVersion = new IntegerSetting(p, "preference_previous_version", 0);

        proxyEnabled = new BooleanSetting(p, "preference_proxy_enabled", false);
        proxyAddress = new StringSetting(p, "preference_proxy_address", "");
        proxyPort = new IntegerSetting(p, "preference_proxy_port", 80);

        proxyEnabled.addCallback((setting, value) -> loadProxy());
        proxyAddress.addCallback((setting, value) -> loadProxy());
        proxyPort.addCallback((setting, value) -> loadProxy());
        loadProxy();

        historyOpenCounter = new CounterSetting(p, "counter_history_open");
        threadOpenCounter = new CounterSetting(p, "counter_thread_open");

        updateCheckTime = new LongSetting(p, "update_check_time", 0L);
        updateCheckInterval = new LongSetting(p, "update_check_interval", UpdateManager.DEFAULT_UPDATE_CHECK_INTERVAL_MS);
        autoCheckUpdates = new BooleanSetting(p, "preference_auto_check_updates", false);

        pinnedSearches = new StringSetting(p, "preference_pinned_searches", "[]");
        crashReporting = new BooleanSetting(p, "preference_crash_reporting", true);
        reencodeHintShown = new BooleanSetting(p, "preference_reencode_hint_already_shown", false);
        setupSitesBoardsHintShown = new BooleanSetting(p, "setup_sites_boards_hint_already_shown", false);

        dnsOverHttps = new BooleanSetting(p, "dns_over_https", false);

        customUserAgent = new StringSetting(p, "custom_user_agent", "");
        customCFClearanceCommand = new StringSetting(p, "custom_cfclearance_command", "");

        appIconMode = new OptionsSetting<>(p, "preference_app_icon_mode", AppIconMode.class, AppIconMode.BLUE);

        enableLocalization = new BooleanSetting(p, "preference_enable_localization", false);

    }

    public static boolean isCrashReportingAvailable() {
        return false;
    }

    public static boolean isCrashReportingEnabled() {
        return isCrashReportingAvailable() && crashReporting.get();
    }

    public static ThemeColor getThemeAndColor() {
        String themeRaw = ChanSettings.theme.get();

        String theme = "auto";
        String color = "blue";
        String accentColor = "blue";
        String loadingBarColor = null;

        String[] splitted = themeRaw.split(",");
        if (splitted.length >= 1) theme = splitted[0];
        if (splitted.length >= 2) color = splitted[1];
        if (splitted.length >= 3) accentColor = splitted[2];
        if (splitted.length >= 4) loadingBarColor = splitted[3];

        return new ThemeColor(theme, color, accentColor, loadingBarColor);
    }

    public static void setThemeAndColor(ThemeColor themeColor) {
        if (TextUtils.isEmpty(themeColor.theme) || TextUtils.isEmpty(themeColor.color) || TextUtils.isEmpty(themeColor.accentColor)) {
            throw new IllegalArgumentException();
        }
        
        StringBuilder value = new StringBuilder();
        value.append(themeColor.theme).append(",")
             .append(themeColor.color).append(",")
             .append(themeColor.accentColor);
             
        if (!TextUtils.isEmpty(themeColor.loadingBarColor)) {
            value.append(",").append(themeColor.loadingBarColor);
        }
        
        ChanSettings.theme.set(value.toString());
    }

    public static List<CustomTheme> getCustomThemes() {
        String raw = customThemes.get();
        if (TextUtils.isEmpty(raw)) return new ArrayList<>();
        Type type = new TypeToken<List<CustomTheme>>() {}.getType();
        try {
            List<CustomTheme> parsed = new Gson().fromJson(raw, type);
            if (parsed == null) return new ArrayList<>();
            // Filter out broken themes (e.g. from old obfuscated formats that couldn't be migrated)
            parsed.removeIf(t -> t.name == null || t.displayName == null);
            return parsed;
        } catch (Exception e) {
            Logger.e("ChanSettings", "Invalid custom theme data, resetting to defaults", e);
            customThemes.set("[]");
            AndroidUtils.runOnUiThread(() ->
                    Toast.makeText(AndroidUtils.getAppContext(), R.string.settings_custom_theme_reset, Toast.LENGTH_LONG).show());
            return new ArrayList<>();
        }
    }

    public static void saveCustomThemes(List<CustomTheme> themes) {
        customThemes.set(new Gson().toJson(themes));
    }

    public static List<PinnedSearch> getPinnedSearches() {
        try {
            Type type = new TypeToken<List<PinnedSearch>>() {}.getType();
            List<PinnedSearch> list = new Gson().fromJson(pinnedSearches.get(), type);
            if (list == null) return new ArrayList<>();
            return list;
        } catch (Exception e) {
            Logger.e("ChanSettings", "Error loading pinned searches", e);
            return new ArrayList<>();
        }
    }

    public static void savePinnedSearches(List<PinnedSearch> searches) {
        pinnedSearches.set(new Gson().toJson(searches));
    }

    /**
     * Returns a {@link Proxy} if a proxy is enabled, <tt>null</tt> otherwise.
     *
     * @return a proxy or null
     */
    public static Proxy getProxy() {
        return proxy;
    }

    /** Call after restoring settings from backup so proxy is updated. */
    public static void reloadProxy() {
        loadProxy();
    }

    private static void loadProxy() {
        if (proxyEnabled.get()) {
            proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyAddress.get(), proxyPort.get()));
        } else {
            proxy = null;
        }
    }

    public static class ThemeColor {
        public String theme;
        public String color;
        public String accentColor;
        public String loadingBarColor;

        public ThemeColor(String theme, String color, String accentColor) {
            this(theme, color, accentColor, null);
        }

        public ThemeColor(String theme, String color, String accentColor, String loadingBarColor) {
            this.theme = theme;
            this.color = color;
            this.accentColor = accentColor;
            this.loadingBarColor = loadingBarColor;
        }
    }

    public static class CustomTheme {
        @SerializedName(value = "displayName", alternate = {"a"})
        public String displayName;
        @SerializedName(value = "name", alternate = {"b"})
        public String name;
        @SerializedName(value = "baseTheme", alternate = {"c"})
        public String baseTheme;
        @SerializedName(value = "isLightTheme", alternate = {"d"})
        public boolean isLightTheme;
        @SerializedName(value = "colorOverrides", alternate = {"e"})
        public Map<String, Integer> colorOverrides;
        
        public CustomTheme(String displayName, String name, String baseTheme, boolean isLightTheme, Map<String, Integer> colorOverrides) {
            this.displayName = displayName;
            this.name = name;
            this.baseTheme = baseTheme;
            this.isLightTheme = isLightTheme;
            this.colorOverrides = colorOverrides;
        }
    }

    public static class PinnedSearch {
        public String boardCode;
        public String searchTerm;
        public int siteClassId;

        public PinnedSearch(String boardCode, String searchTerm) {
            this(boardCode, searchTerm, 0);
        }

        public PinnedSearch(String boardCode, String searchTerm, int siteClassId) {
            this.boardCode = boardCode;
            this.searchTerm = searchTerm;
            this.siteClassId = siteClassId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PinnedSearch that = (PinnedSearch) o;
            return siteClassId == that.siteClassId &&
                    Objects.equals(boardCode, that.boardCode) &&
                    Objects.equals(searchTerm, that.searchTerm);
        }

        @Override
        public int hashCode() {
            return Objects.hash(boardCode, searchTerm, siteClassId);
        }
    }

    public static class SettingChanged<T> {
        public final Setting<T> setting;

        public SettingChanged(Setting<T> setting) {
            this.setting = setting;
        }
    }
}
