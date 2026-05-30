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

import static org.otacoo.chan.Chan.injector;

import android.app.AlertDialog;
import android.content.Context;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.helper.RefreshUIMessage;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.IntegerSettingView;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.StringSettingView;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.Locale;

import de.greenrobot.event.EventBus;

public class MiscSettingsController extends SettingsController {
    private SettingView proxyEnabledView;
    private SettingView dnsOverHttpsView;

    public MiscSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        navigation.setTitle(R.string.settings_screen_misc);

        setupLayout();
        rebuildPreferences();
    }

    private void rebuildPreferences() {
        populatePreferences();
        buildPreferences();
    }

    private void populatePreferences() {
        requiresUiRefresh.clear();
        groups.clear();
        requiresRestart.clear();


        // Reset group
        {
            SettingsGroup reset = new SettingsGroup(R.string.setting_group_reset);

            android.content.res.Resources sysRes = android.content.res.Resources.getSystem();
            Locale sysLocale = sysRes.getConfiguration().getLocales().get(0);
            String localizationDesc = ChanSettings.enableLocalization.get()
                    ? sysLocale.getDisplayName(sysLocale)
                    : context.getString(R.string.setting_enable_localization_description);
            requiresRestart.add(reset.add(new BooleanSettingView(this,
                    ChanSettings.enableLocalization,
                    context.getString(R.string.setting_enable_localization),
                    localizationDesc)));            

            setupClearThreadHidesSetting(reset);

            reset.add(new LinkSettingView(this, R.string.setting_cookies_view_edit, 0, v -> {
                navigationController.pushController(new CookieManagerController(context));
            }));

            groups.add(reset);
        }

        // Proxy group
        {
            SettingsGroup proxy = new SettingsGroup(R.string.settings_group_proxy);

            proxyEnabledView = proxy.add(new BooleanSettingView(this, ChanSettings.proxyEnabled,
                    R.string.setting_proxy_enabled, 0));

            proxy.add(new StringSettingView(this, ChanSettings.proxyAddress,
                    R.string.setting_proxy_address, R.string.setting_proxy_address));

            proxy.add(new IntegerSettingView(this, ChanSettings.proxyPort,
                    R.string.setting_proxy_port, R.string.setting_proxy_port));

            proxy.add(new StringSettingView(this, ChanSettings.proxyUsername,
                    R.string.setting_proxy_username, R.string.setting_proxy_username));

            proxy.add(new StringSettingView(this, ChanSettings.proxyPassword,
                    R.string.setting_proxy_password, R.string.setting_proxy_password));

            groups.add(proxy);

            // DNS Over HTTP Group
            {
                SettingsGroup doh = new SettingsGroup(R.string.setting_group_dns_over_https);

                dnsOverHttpsView = doh.add(new BooleanSettingView(this, ChanSettings.dnsOverHttps,
                        R.string.setting_group_dns_enable, R.string.setting_group_dns_enable_description));

                groups.add(doh);
            }

            // User-Agent group
            {
                SettingsGroup ua = new SettingsGroup(R.string.setting_group_user_agent);

                ua.add(new StringSettingView(this, ChanSettings.customUserAgent,
                        R.string.setting_group_user_agent_ua, R.string.setting_group_user_agent_ua_desc));
                groups.add(ua);
            }
        }
    }

    @Override
    public void onPreferenceChange(SettingView item) {
        if (item == proxyEnabledView && ChanSettings.proxyEnabled.get()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.proxy_warning_title)
                    .setMessage(R.string.proxy_warning_message)
                    .setPositiveButton(R.string.ok, null)
                    .setNegativeButton(R.string.cancel, (dialog, which) -> {
                        ChanSettings.proxyEnabled.set(false);
                        SwitchCompat switcher = item.view.findViewById(R.id.switcher);
                        if (switcher != null) {
                            switcher.setChecked(false);
                        }
                    })
                    .show();
        }
        if (item == dnsOverHttpsView && ChanSettings.dnsOverHttps.get()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.doh_warning_title)
                    .setMessage(R.string.doh_warning_message)
                    .setPositiveButton(R.string.ok, null)
                    .setNegativeButton(R.string.cancel, (dialog, which) -> {
                        ChanSettings.dnsOverHttps.set(false);
                        SwitchCompat switcher = item.view.findViewById(R.id.switcher);
                        if (switcher != null) {
                            switcher.setChecked(false);
                        }
                    })
                    .show();
        }
        super.onPreferenceChange(item);
    }

    private void setupClearThreadHidesSetting(SettingsGroup post) {
        post.add(new LinkSettingView(this, R.string.setting_clear_thread_hides, 0, v -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.setting_confirm_clear_hides)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        DatabaseManager databaseManager = injector().instance(DatabaseManager.class);
                        databaseManager.runTask(
                                databaseManager.getDatabaseHideManager().clearAllThreadHides());
                        AndroidUtils.showThemedSnackbar(view, R.string.setting_cleared_thread_hides, Snackbar.LENGTH_LONG);
                        EventBus.getDefault().post(new RefreshUIMessage("clearhides"));
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
    }
}
