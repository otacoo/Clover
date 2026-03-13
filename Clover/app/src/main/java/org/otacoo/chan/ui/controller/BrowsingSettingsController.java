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

import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.content.Context;

import org.otacoo.chan.R;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.ListSettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.ui.settings.StringSettingView;

import java.util.ArrayList;
import java.util.List;

public class BrowsingSettingsController extends SettingsController {

    public BrowsingSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        navigation.setTitle(R.string.settings_screen_browsing);

        setupLayout();
        populatePreferences();
        buildPreferences();
    }

    private void populatePreferences() {
        requiresUiRefresh.clear();
        groups.clear();
        requiresRestart.clear();

        // Boards group
        {
            SettingsGroup boards = new SettingsGroup(R.string.settings_group_boards);

            boards.add(new BooleanSettingView(this, ChanSettings.confirmExit,
                    R.string.setting_confirm_exit, 0));

            requiresRestart.add(boards.add(new BooleanSettingView(this,
                    ChanSettings.controllerSwipeable,
                    R.string.setting_controller_swipeable, 0)));

            boards.add(new BooleanSettingView(this,
                    ChanSettings.volumeKeysScrolling,
                    R.string.setting_volume_key_scrolling, 0));

            boards.add(new BooleanSettingView(this,
                    ChanSettings.openLinkBrowser,
                    R.string.setting_open_link_browser, 0));

            boards.add(new BooleanSettingView(this,
                    ChanSettings.openLinkConfirmation,
                    R.string.setting_open_link_confirmation, 0));

            groups.add(boards);
        }

        // Threads group
        {
            SettingsGroup threads = new SettingsGroup(R.string.settings_group_threads);

            threads.add(new BooleanSettingView(this,
                    ChanSettings.autoRefreshThread,
                    R.string.setting_auto_refresh_thread, 0));

            threads.add(new BooleanSettingView(this, ChanSettings.postPinThread,
                    R.string.setting_post_pin, 0));

            groups.add(threads);
        }

        // Posts group
        {
            SettingsGroup posts = new SettingsGroup(R.string.settings_group_posts);

            posts.add(new StringSettingView(this, ChanSettings.postDefaultName,
                    R.string.setting_post_default_name, R.string.setting_post_default_name));

            requiresUiRefresh.add(posts.add(new BooleanSettingView(this,
                    ChanSettings.anonymize,
                    R.string.setting_anonymize, 0)));

            requiresUiRefresh.add(posts.add(new BooleanSettingView(this,
                    ChanSettings.showAnonymousName,
                    R.string.setting_show_anonymous_name, 0)));

            posts.add(new BooleanSettingView(this,
                    ChanSettings.tapNoReply,
                    R.string.setting_tap_no_rely, 0));

            requiresUiRefresh.add(posts.add(new BooleanSettingView(this,
                    ChanSettings.postFullDate,
                    R.string.setting_post_full_date, 0)));

            requiresUiRefresh.add(posts.add(new BooleanSettingView(this,
                    ChanSettings.postFileInfo,
                    R.string.setting_post_file_info, 0)));

            requiresUiRefresh.add(posts.add(new BooleanSettingView(this,
                    ChanSettings.postFilename,
                    R.string.setting_post_filename, 0)));

            groups.add(posts);
        }

        // Misc group
        {
            SettingsGroup misc = new SettingsGroup(R.string.settings_group_misc);

            requiresUiRefresh.add(misc.add(new BooleanSettingView(this,
                    ChanSettings.anonymizeIds,
                    R.string.setting_anonymize_ids, 0)));

            setupHideFlagsSetting(misc);

            requiresUiRefresh.add(misc.add(new BooleanSettingView(this,
                    ChanSettings.textOnly,
                    R.string.setting_text_only, R.string.setting_text_only_description)));

            requiresUiRefresh.add(misc.add(new BooleanSettingView(this,
                    ChanSettings.revealTextSpoilers,
                    R.string.settings_reveal_text_spoilers,
                    R.string.settings_reveal_text_spoilers_description)));

            groups.add(misc);
        }
    }

    private void setupHideFlagsSetting(SettingsGroup misc) {
        List<ListSettingView.Item<?>> hideFlagsModes = new ArrayList<>();
        for (ChanSettings.HideFlagsMode mode : ChanSettings.HideFlagsMode.values()) {
            int name = 0;
            switch (mode) {
                case DISABLED:
                    name = R.string.setting_hide_flags_disabled;
                    break;
                case ALL:
                    name = R.string.setting_hide_flags_all;
                    break;
                case CATALOG:
                    name = R.string.setting_hide_flags_catalog;
                    break;
                case THREAD:
                    name = R.string.setting_hide_flags_thread;
                    break;
            }
            hideFlagsModes.add(new ListSettingView.Item<>(getString(name), mode));
        }

        requiresUiRefresh.add(misc.add(new ListSettingView<>(this,
                ChanSettings.hideFlags,
                R.string.setting_hide_flags, hideFlagsModes)));
    }
}
