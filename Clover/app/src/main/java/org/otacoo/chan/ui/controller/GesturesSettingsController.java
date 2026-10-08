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

import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.content.Context;

import org.otacoo.chan.R;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.ListSettingView;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;

import java.util.ArrayList;
import java.util.List;

public class GesturesSettingsController extends SettingsController {
    private ListSettingView<ChanSettings.SwipeGesture> swipeToCloseView;
    private ListSettingView<ChanSettings.SwipeGesture> swipeToSaveView;

    public GesturesSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        navigation.setTitle(R.string.settings_screen_gestures);

        setupLayout();
        populatePreferences();
        buildPreferences();

        onPreferenceChange(swipeToCloseView);
    }

    @Override
    public void onPreferenceChange(SettingView item) {
        super.onPreferenceChange(item);

        if (item == swipeToCloseView || item == swipeToSaveView) {
            updateGestures();
        }
    }

    private void populatePreferences() {
        // Gestures group
        {
            SettingsGroup gestures = new SettingsGroup(R.string.settings_group_media_gestures);

            List<ListSettingView.Item<?>> swipeGestureTypes = new ArrayList<>();
            swipeGestureTypes.add(new ListSettingView.Item<>(getString(R.string.swipe_gesture_none), ChanSettings.SwipeGesture.NONE));
            swipeGestureTypes.add(new ListSettingView.Item<>(getString(R.string.swipe_gesture_up), ChanSettings.SwipeGesture.UP));
            swipeGestureTypes.add(new ListSettingView.Item<>(getString(R.string.swipe_gesture_down), ChanSettings.SwipeGesture.DOWN));

            ArrayList<ListSettingView.Item<?>> closeItems = new ArrayList<>();
            for (ListSettingView.Item<?> item : swipeGestureTypes) {
                closeItems.add(new ListSettingView.Item<>(item.name, item.key, item.enabled));
            }
            swipeToCloseView = new ListSettingView<>(this,
                    ChanSettings.swipeToClose,
                    R.string.setting_swipe_to_close,
                    closeItems);
            gestures.add(swipeToCloseView);

            ArrayList<ListSettingView.Item<?>> saveItems = new ArrayList<>();
            for (ListSettingView.Item<?> item : swipeGestureTypes) {
                saveItems.add(new ListSettingView.Item<>(item.name, item.key, item.enabled));
            }
            swipeToSaveView = new ListSettingView<>(this,
                    ChanSettings.swipeToSave,
                    R.string.setting_swipe_to_save,
                    saveItems);
            gestures.add(swipeToSaveView);

            gestures.add(new BooleanSettingView(this, ChanSettings.tapToClose,
                    R.string.setting_tap_to_close,
                    R.string.setting_tap_to_close_description));
            gestures.add(new BooleanSettingView(this, ChanSettings.fingerRotate, R.string.setting_finger_rotate, 0));
            gestures.add(new BooleanSettingView(this, ChanSettings.swipeWhileZoomedIn, R.string.setting_swipe_zoomed, 0));
            gestures.add(new BooleanSettingView(this, ChanSettings.doubleTapPlayPause, R.string.setting_double_tap_play_pause, 0));
            gestures.add(new BooleanSettingView(this, ChanSettings.videoZoom,
                    R.string.setting_video_zoom,
                    R.string.setting_video_zoom_description));
            gestures.add(new BooleanSettingView(this, ChanSettings.showZoomLevel,
                    R.string.setting_show_zoom_level,
                    R.string.setting_show_zoom_level_description));

            groups.add(gestures);
        }
    }

    private void updateGestures() {
        if (swipeToCloseView == null || swipeToSaveView == null) return;

        ChanSettings.SwipeGesture closeGesture = ChanSettings.swipeToClose.get();
        ChanSettings.SwipeGesture saveGesture = ChanSettings.swipeToSave.get();

        for (int i = 0; i < swipeToCloseView.items.size(); i++) {
            ListSettingView.Item<?> closeItem = swipeToCloseView.items.get(i);
            ListSettingView.Item<?> saveItem = swipeToSaveView.items.get(i);

            if (closeItem.key == ChanSettings.SwipeGesture.UP) {
                closeItem.enabled = saveGesture != ChanSettings.SwipeGesture.UP;
                saveItem.enabled = closeGesture != ChanSettings.SwipeGesture.UP;
            } else if (closeItem.key == ChanSettings.SwipeGesture.DOWN) {
                closeItem.enabled = saveGesture != ChanSettings.SwipeGesture.DOWN;
                saveItem.enabled = closeGesture != ChanSettings.SwipeGesture.DOWN;
            }
        }
    }
}
