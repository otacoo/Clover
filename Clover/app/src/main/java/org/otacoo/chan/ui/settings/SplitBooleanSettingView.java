/*
 * Clover - 4chan browser https://github.com/otacoo/Clover
 * Copyright (C) 2026 otacoo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.otacoo.chan.ui.settings;

import android.view.View;
import android.widget.CompoundButton;

import androidx.appcompat.widget.SwitchCompat;

import org.otacoo.chan.R;
import org.otacoo.chan.core.settings.Setting;

public class SplitBooleanSettingView extends SettingView implements CompoundButton.OnCheckedChangeListener {
    private SwitchCompat switcher;
    private final Setting<Boolean> setting;
    private final String description;
    private final View.OnClickListener mainClickListener;
    private boolean building = true;

    public SplitBooleanSettingView(SettingsController settingsController, Setting<Boolean> setting, int name, int description, View.OnClickListener mainClickListener) {
        this(settingsController, setting, getString(name), getString(description), mainClickListener);
    }

    public SplitBooleanSettingView(SettingsController settingsController, Setting<Boolean> setting, String name, String description, View.OnClickListener mainClickListener) {
        super(settingsController, name);
        this.setting = setting;
        this.description = description;
        this.mainClickListener = mainClickListener;
    }

    @Override
    public void setView(View view) {
        super.setView(view);

        view.findViewById(R.id.main_area).setOnClickListener(mainClickListener);

        View switchArea = view.findViewById(R.id.switch_area);
        switcher = view.findViewById(R.id.switcher);

        switchArea.setOnClickListener(v -> switcher.toggle());
        switcher.setOnCheckedChangeListener(this);

        switcher.setChecked(setting.get());

        building = false;
    }

    @Override
    public String getBottomDescription() {
        return description;
    }

    @Override
    public void setEnabled(boolean enabled) {
        view.setEnabled(enabled);
        view.findViewById(R.id.main_area).setEnabled(enabled);
        view.findViewById(R.id.switch_area).setEnabled(enabled);
        view.findViewById(R.id.top).setEnabled(enabled);
        View bottom = view.findViewById(R.id.bottom);
        if (bottom != null) {
            bottom.setEnabled(enabled);
        }
        switcher.setEnabled(enabled);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (!building) {
            setting.set(isChecked);
            settingsController.onPreferenceChange(this);
        }
    }
}
