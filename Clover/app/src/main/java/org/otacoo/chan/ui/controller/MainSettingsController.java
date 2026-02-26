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

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.otacoo.chan.R;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.presenter.SettingsPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.settings.SettingsBackupRestore;
import org.otacoo.chan.ui.activity.ActivityResultHelper;
import org.otacoo.chan.utils.Logger;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.ui.settings.SplitBooleanSettingView;
import org.otacoo.chan.utils.AndroidUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

import javax.inject.Inject;

import pl.droidsonroids.gif.GifImageView;

public class MainSettingsController extends SettingsController implements SettingsPresenter.Callback {
    @Inject
    private SettingsPresenter presenter;
    @Inject
    private ActivityResultHelper resultHelper;
    @Inject
    private DatabaseManager databaseManager;

    private LinkSettingView watchLink;
    private int clickCount;
    private SettingView developerView;
    private LinkSettingView sitesSetting;
    private LinkSettingView filtersSetting;
    private SettingView crashReportSetting;

    public MainSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        navigation.setTitle(R.string.settings_screen);

        setupLayout();

        populatePreferences();

        buildPreferences();

        if (!ChanSettings.developer.get()) {
            developerView.view.setVisibility(View.GONE);
        }

        presenter.create(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        presenter.destroy();
    }

    @Override
    public void onShow() {
        super.onShow();

        presenter.show();
    }

    @Override
    public void setFiltersCount(int count) {
        String filters = context.getResources().getQuantityString(R.plurals.filter, count, count);
        filtersSetting.setDescription(filters);
    }

    @Override
    public void setSiteCount(int count) {
        String sites = context.getResources().getQuantityString(R.plurals.site, count, count);
        sitesSetting.setDescription(sites);
    }

    @Override
    public void setWatchEnabled(boolean enabled) {
        watchLink.setDescription(enabled ?
                R.string.setting_watch_summary_enabled : R.string.setting_watch_summary_disabled);
    }

    @Override
    public void onPreferenceChange(SettingView item) {
        super.onPreferenceChange(item);
        if (item == crashReportSetting) {
            Toast.makeText(context, R.string.settings_crash_reporting_toggle_notice,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void populatePreferences() {
        // General group
        {
            SettingsGroup general = new SettingsGroup(R.string.settings_group_settings);

            watchLink = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_watch, 0,
                    v -> navigationController.pushController(
                            new WatchSettingsController(context))));

            sitesSetting = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_sites, 0,
                    v -> navigationController.pushController(
                            new SitesSetupController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_appearance, R.string.settings_appearance_description,
                    v -> navigationController.pushController(
                            new AppearanceSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_behavior, R.string.settings_behavior_description,
                    v -> navigationController.pushController(
                            new BehaviourSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_media, R.string.settings_media_description,
                    v -> navigationController.pushController(
                            new MediaSettingsController(context))));

            filtersSetting = (LinkSettingView) general.add(new LinkSettingView(this,
                    R.string.settings_filters, 0,
                    v -> navigationController.pushController(new FiltersController(context))));

            groups.add(general);
        }

        setupBackupRestoreGroup();
        setupAboutGroup();
    }

    private void setupBackupRestoreGroup() {
        SettingsGroup backupRestore = new SettingsGroup(R.string.settings_group_backup_restore);
        backupRestore.add(new LinkSettingView(this,
                R.string.settings_backup, R.string.settings_backup_description,
                v -> launchBackup()));
        backupRestore.add(new LinkSettingView(this,
                R.string.settings_restore, R.string.settings_restore_description,
                v -> launchRestore()));
        groups.add(backupRestore);
    }

    private void launchBackup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Toast.makeText(context, R.string.settings_backup_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = String.format(Locale.US, "clover_settings_%tY-%<tm-%<td_%<tH-%<tM-%<tS.json", Calendar.getInstance());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        resultHelper.getResultFromIntent(intent, (resultCode, result) -> {
            if (resultCode != Activity.RESULT_OK || result == null || result.getData() == null) {
                return;
            }
            Uri uri = result.getData();
            try {
                String json = SettingsBackupRestore.exportFull(databaseManager, AndroidUtils.getPreferences());
                ContentResolver cr = context.getContentResolver();
                try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                    if (os != null) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                }
                Toast.makeText(context, R.string.settings_backup_success, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, R.string.settings_backup_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void launchRestore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Toast.makeText(context, R.string.settings_restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        resultHelper.getResultFromIntent(intent, (resultCode, result) -> {
            if (resultCode != Activity.RESULT_OK || result == null || result.getData() == null) {
                return;
            }
            Uri uri = result.getData();
            try {
                ContentResolver cr = context.getContentResolver();
                StringBuilder sb = new StringBuilder();
                try (InputStream is = cr.openInputStream(uri)) {
                    if (is == null) {
                        throw new Exception("Could not open file");
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                SettingsBackupRestore.importFull(databaseManager, AndroidUtils.getPreferences(), sb.toString());
                ChanSettings.reloadProxy();
                Toast.makeText(context, R.string.settings_restore_success, Toast.LENGTH_LONG).show();
                StartActivity startActivity = getStartActivity(context);
                if (startActivity != null) {
                    startActivity.restartApp();
                }
            } catch (Exception e) {
                Logger.e("MainSettingsController", "Restore failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : context.getString(R.string.settings_restore_failed);
                Toast.makeText(context, context.getString(R.string.settings_restore_failed) + ": " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Unwraps context (e.g. ContextWrapper) to find StartActivity so restore can trigger restart. */
    private static StartActivity getStartActivity(Context context) {
        while (context != null) {
            if (context instanceof StartActivity) {
                return (StartActivity) context;
            }
            context = context instanceof ContextWrapper ? ((ContextWrapper) context).getBaseContext() : null;
        }
        return null;
    }

    private void setupAboutGroup() {
        SettingsGroup about = new SettingsGroup(R.string.settings_group_about);

        final String version = setupVersionSetting(about);

        setupUpdateSetting(about);

        setupCrashReportingSetting(about);

        setupExtraAboutSettings(about, version);

        about.add(new LinkSettingView(this,
                R.string.settings_about_license, R.string.settings_about_license_description,
                v -> navigationController.pushController(
                        new LicensesController(context,
                                getString(R.string.settings_about_license),
                                "file:///android_asset/html/license.html"))));

        about.add(new LinkSettingView(this,
                R.string.settings_about_licenses, R.string.settings_about_licenses_description,
                v -> navigationController.pushController(
                        new LicensesController(context,
                                getString(R.string.settings_about_licenses),
                                "file:///android_asset/html/licenses.html"))));

        developerView = about.add(new LinkSettingView(this,
                R.string.settings_developer, 0,
                v -> navigationController.pushController(
                        new DeveloperSettingsController(context))));

        groups.add(about);
    }

    private void setupExtraAboutSettings(SettingsGroup about, String version) {
        int extraAbouts = context.getResources()
                .getIdentifier("extra_abouts", "array", context.getPackageName());

        if (extraAbouts != 0) {
            String[] abouts = context.getResources().getStringArray(extraAbouts);
            if (abouts.length % 3 == 0) {
                for (int i = 0, aboutsLength = abouts.length; i < aboutsLength; i += 3) {
                    String aboutName = abouts[i];
                    String aboutDescription = abouts[i + 1];
                    if (TextUtils.isEmpty(aboutDescription)) {
                        aboutDescription = null;
                    }
                    String aboutLink = abouts[i + 2];
                    if (TextUtils.isEmpty(aboutLink)) {
                        aboutLink = null;
                    }

                    final String finalAboutLink = aboutLink;
                    View.OnClickListener clickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (finalAboutLink != null) {
                                if (finalAboutLink.contains("__EMAIL__")) {
                                    String[] email = finalAboutLink.split("__EMAIL__");
                                    Intent intent = new Intent(Intent.ACTION_SENDTO);
                                    intent.setData(Uri.parse("mailto:"));
                                    intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email[0]});
                                    String subject = email[1];
                                    subject = subject.replace("__VERSION__", version);
                                    intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                                    AndroidUtils.openIntent(intent);
                                } else {
                                    AndroidUtils.openLink(finalAboutLink);
                                }
                            }
                        }
                    };

                    about.add(new LinkSettingView(this,
                            aboutName, aboutDescription,
                            clickListener));
                }
            }
        }
    }

    private String setupVersionSetting(SettingsGroup about) {
        String version = "";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        String userVersion = version + " " + getString(R.string.app_flavor_name);
        about.add(new LinkSettingView(this,
                getString(R.string.app_name), userVersion,
                v -> {
                    if ((++clickCount) % 5 == 0) {
                        boolean developer = !ChanSettings.developer.get();

                        ChanSettings.developer.set(developer);

                        Toast.makeText(context, (developer ? "Enabled" : "Disabled") +
                                " developer options", Toast.LENGTH_LONG).show();

                        developerView.view.setVisibility(developer ? View.VISIBLE : View.GONE);
                    }
                    final GifImageView iv = new GifImageView(context);
                    iv.setImageResource(R.drawable.ic_task_description);
                    if (iv.getDrawable() != null) {
                        iv.setX(-iv.getDrawable().getIntrinsicWidth());
                        iv.setY(navigationController.view.getHeight() - iv.getDrawable().getIntrinsicHeight());
                        navigationController.view.addView(iv);
                        iv.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        iv.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        iv.setLayoutParams(iv.getLayoutParams());
                        ValueAnimator animator = ValueAnimator.ofFloat(iv.getX() - 100, navigationController.view.getWidth() + 100);
                        animator.setDuration(7500);
                        animator.addUpdateListener(animation ->
                                iv.setX((float) animation.getAnimatedValue()));
                        animator.setRepeatCount(ValueAnimator.INFINITE);
                        animator.start();
                    }
                }));

        return version;
    }

    private void setupUpdateSetting(SettingsGroup about) {
        if (((StartActivity) context).getVersionHandler().isUpdatingAvailable()) {
            about.add(new SplitBooleanSettingView(this,
                    ChanSettings.autoCheckUpdates,
                    R.string.settings_update_check,
                    R.string.settings_update_check_description,
                    v -> ((StartActivity) context).getVersionHandler().manualUpdateCheck()));
        }
    }

    private void setupCrashReportingSetting(SettingsGroup about) {
        if (ChanSettings.isCrashReportingAvailable()) {
            crashReportSetting = about.add(new BooleanSettingView(this,
                    ChanSettings.crashReporting,
                    R.string.settings_crash_reporting,
                    R.string.settings_crash_reporting_description));
        }
    }
}
