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
import android.os.Build;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.otacoo.chan.BuildConfig;
import org.otacoo.chan.Chan;
import org.otacoo.chan.R;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.ui.helper.RefreshUIMessage;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.IntegerSettingView;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.ui.settings.StringSettingView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.greenrobot.event.EventBus;

public class MiscSettingsController extends SettingsController {


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
            Locale sysLocale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? sysRes.getConfiguration().getLocales().get(0)
                    : sysRes.getConfiguration().locale;
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

            proxy.add(new BooleanSettingView(this, ChanSettings.proxyEnabled,
                    R.string.setting_proxy_enabled, 0));

            proxy.add(new StringSettingView(this, ChanSettings.proxyAddress,
                    R.string.setting_proxy_address, R.string.setting_proxy_address));

            proxy.add(new IntegerSettingView(this, ChanSettings.proxyPort,
                    R.string.setting_proxy_port, R.string.setting_proxy_port));

            groups.add(proxy);

            // DNS Over HTTP Group
            {
                SettingsGroup doh = new SettingsGroup(R.string.setting_group_dns_over_https);

                doh.add(new BooleanSettingView(this, ChanSettings.dnsOverHttps,
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

    private void setupClearThreadHidesSetting(SettingsGroup post) {
        post.add(new LinkSettingView(this, R.string.setting_clear_thread_hides, 0, v -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.setting_confirm_clear_hides)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        DatabaseManager databaseManager = injector().instance(DatabaseManager.class);
                        databaseManager.runTask(
                                databaseManager.getDatabaseHideManager().clearAllThreadHides());
                        Toast.makeText(context, R.string.setting_cleared_thread_hides, Toast.LENGTH_LONG)
                                .show();
                        EventBus.getDefault().post(new RefreshUIMessage("clearhides"));
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
    }
}
