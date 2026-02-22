/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens 
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
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
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.otacoo.chan.R;
import org.otacoo.chan.core.presenter.StorageSetupPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.IntegerSettingView;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.ListSettingView;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

public class MediaSettingsController extends SettingsController implements
        StorageSetupPresenter.Callback {
    private static final int OPEN_TREE_INTENT_RESULT_ID = 101;

    @Inject
    private StorageSetupPresenter presenter;

    // Special setting views
    private LinkSettingView saveLocation;
    private ListSettingView<ChanSettings.MediaAutoLoadMode> imageAutoLoadView;
    private ListSettingView<ChanSettings.MediaAutoLoadMode> videoAutoLoadView;

    public MediaSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        inject(this);

        navigation.setTitle(R.string.settings_screen_media);

        setupLayout();

        populatePreferences();

        buildPreferences();

        onPreferenceChange(imageAutoLoadView);

        presenter.create(this);
    }

    private void requestTree() {
//        Intent i = storage.getOpenTreeIntent();
//        ((Activity) context).startActivityForResult(i, OPEN_TREE_INTENT_RESULT_ID);
//        updateName();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == OPEN_TREE_INTENT_RESULT_ID && resultCode == Activity.RESULT_OK) {
//            storage.handleOpenTreeIntent(intent);
//            updateName();
        }
    }

    @Override
    public void onPreferenceChange(SettingView item) {
        super.onPreferenceChange(item);

        if (item == imageAutoLoadView) {
            updateVideoLoadModes();
        }
    }

    @Override
    public void setSaveLocationDescription(String description) {
        saveLocation.setDescription(description);
    }

    @Override
    public void showPathDialog(String path) {
        FrameLayout container = new FrameLayout(context);
        EditText dialogView = new EditText(context);
        dialogView.setText(path);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = lp.rightMargin = dp(20);
        container.addView(dialogView, lp);
        new AlertDialog.Builder(context)
                .setTitle(R.string.save_location_screen)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    presenter.saveLocationEntered(dialogView.getText().toString());
                })
                .show();
    }

    private void populatePreferences() {
        // Media group
        {
            SettingsGroup media = new SettingsGroup(R.string.settings_group_media);

            setupSaveLocationSetting(media);

            //Save modifications
            media.add(new ListSettingView<>(this,
                    ChanSettings.saveImageFolder,
                    R.string.setting_save_image_folder, createDestinationFolderList()));

            media.add(new ListSettingView<>(this,
                    ChanSettings.saveAlbumFolder,
                    R.string.setting_save_album_folder, createDestinationFolderList()));

            media.add(new BooleanSettingView(this,
                    ChanSettings.saveOriginalFilename,
                    R.string.setting_save_original_filename,
                    R.string.setting_save_original_filename_description));

            media.add(new BooleanSettingView(this,
                    ChanSettings.shareUrl,
                    R.string.setting_share_url, R.string.setting_share_url_description));

            media.add(new BooleanSettingView(this,
                    ChanSettings.revealImageSpoilers,
                    R.string.settings_reveal_image_spoilers,
                    R.string.settings_reveal_image_spoilers_description));

            groups.add(media);
        }

        // Video Player group
        {
            SettingsGroup video = new SettingsGroup(R.string.settings_group_media_video);

            video.add(new BooleanSettingView(this, ChanSettings.videoDefaultMuted,
                    R.string.setting_video_default_muted,
                    R.string.setting_video_default_muted_description));

            video.add(new BooleanSettingView(this, ChanSettings.videoOpenExternal,
                    R.string.setting_video_open_external,
                    R.string.setting_video_open_external_description));

            video.add(new BooleanSettingView(this,
                    ChanSettings.videoAutoLoop,
                    R.string.setting_video_auto_loop,
                    R.string.setting_video_auto_loop_description));

            video.add(new IntegerSettingView(this,
                    ChanSettings.videoExoPlayerTimeout,
                    R.string.setting_video_timeout,
                    R.string.setting_video_timeout_description));

            groups.add(video);
        }

        // Loading group
        {
            SettingsGroup loading = new SettingsGroup(R.string.settings_group_media_loading);

            setupMediaLoadTypesSetting(loading);

            groups.add(loading);
        }
    }

    private void setupMediaLoadTypesSetting(SettingsGroup loading) {
        List<ListSettingView.Item> imageAutoLoadTypes = new ArrayList<>();
        List<ListSettingView.Item> videoAutoLoadTypes = new ArrayList<>();
        for (ChanSettings.MediaAutoLoadMode mode : ChanSettings.MediaAutoLoadMode.values()) {
            int name = 0;
            switch (mode) {
                case ALL:
                    name = R.string.setting_image_auto_load_all;
                    break;
                case WIFI:
                    name = R.string.setting_image_auto_load_wifi;
                    break;
                case NONE:
                    name = R.string.setting_image_auto_load_none;
                    break;
            }

            imageAutoLoadTypes.add(new ListSettingView.Item<>(getString(name), mode));
            videoAutoLoadTypes.add(new ListSettingView.Item<>(getString(name), mode));
        }

        imageAutoLoadView = new ListSettingView<>(this,
                ChanSettings.imageAutoLoadNetwork, R.string.setting_image_auto_load,
                imageAutoLoadTypes);
        loading.add(imageAutoLoadView);

        videoAutoLoadView = new ListSettingView<>(this,
                ChanSettings.videoAutoLoadNetwork, R.string.setting_video_auto_load,
                videoAutoLoadTypes);
        loading.add(videoAutoLoadView);

        updateVideoLoadModes();
    }

    private void updateVideoLoadModes() {
        ChanSettings.MediaAutoLoadMode currentImageLoadMode = ChanSettings.imageAutoLoadNetwork.get();
        ChanSettings.MediaAutoLoadMode[] modes = ChanSettings.MediaAutoLoadMode.values();
        boolean enabled = false;
        boolean resetVideoMode = false;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].getKey().equals(currentImageLoadMode.getKey())) {
                enabled = true;
                if (resetVideoMode) {
                    ChanSettings.videoAutoLoadNetwork.set(modes[i]);
                    videoAutoLoadView.updateSelection();
                    onPreferenceChange(videoAutoLoadView);
                }
            }
            videoAutoLoadView.items.get(i).enabled = enabled;
            if (!enabled && ChanSettings.videoAutoLoadNetwork.get().getKey()
                    .equals(modes[i].getKey())) {
                resetVideoMode = true;
            }
        }
    }

    private void setupSaveLocationSetting(SettingsGroup media) {
        // Register a normal click listener and a long click listener that sets the
        // force file option to true.
        saveLocation = (LinkSettingView) media.add(new LinkSettingView(this,
                R.string.save_location_screen, 0,
                v -> presenter.saveLocationClicked(false)) {
            @Override
            public void setView(View view) {
                super.setView(view);
                view.setOnLongClickListener(v -> {
                    presenter.saveLocationClicked(true);
                    return true;
                });
            }
        });
    }

    private List<ListSettingView.Item> createDestinationFolderList() {
        List<ListSettingView.Item> folderModes = new ArrayList<>();
        for (ChanSettings.DestinationFolderMode mode : ChanSettings.DestinationFolderMode.values()) {
            int name = 0;
            switch (mode) {
                case ROOT:
                    name = R.string.setting_save_mode_root;
                    break;
                case SITE:
                    name = R.string.setting_save_mode_site;
                    break;
                case SITE_BOARD:
                    name = R.string.setting_save_mode_siteboard;
                    break;
                case SITE_BOARD_THREAD:
                    name = R.string.setting_save_mode_siteboardthread;
                    break;
                case BOARD:
                    name = R.string.setting_save_mode_board;
                    break;
                case BOARD_THREAD:
                    name = R.string.setting_save_mode_boardthread;
                    break;
                case LEGACY:
                    name = R.string.setting_save_mode_legacy;
            }
            folderModes.add(new ListSettingView.Item<>(getString(name), mode));
        }
        return folderModes;
    }
}
