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
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.otacoo.chan.R;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.presenter.SettingsPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.settings.SettingsBackupRestore;
import org.otacoo.chan.ui.activity.ActivityResultHelper;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.SettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.ui.settings.SplitBooleanSettingView;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import javax.inject.Inject;

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
                    R.string.settings_boards_threads_posts, R.string.settings_boards_threads_posts_description,
                    v -> navigationController.pushController(
                            new BrowsingSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_media, R.string.settings_media_description,
                    v -> navigationController.pushController(
                            new MediaSettingsController(context))));

            general.add(new LinkSettingView(this,
                    R.string.settings_behavior, R.string.settings_behavior_description,
                    v -> navigationController.pushController(
                            new MiscSettingsController(context))));

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
        // record current media save location so we can restore it after the picker
        String oldSaveLoc = ChanSettings.saveLocation.get();

        String filename = String.format(Locale.US, "clover_settings_%tY-%<tm-%<td_%<tH-%<tM-%<tS.json", Calendar.getInstance());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        resultHelper.getResultFromIntent(intent, (resultCode, result) -> {
            // restore media save location regardless of outcome
            ChanSettings.saveLocation.set(oldSaveLoc);

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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream"
        });
        resultHelper.getResultFromIntent(intent, (resultCode, result) -> {
            if (resultCode != Activity.RESULT_OK || result == null || result.getData() == null) {
                return;
            }
            Uri uri = result.getData();
            try {
                ContentResolver cr = context.getContentResolver();

                // Reject non-JSON files before even reading them
                String mimeType = cr.getType(uri);
                String uriPath = uri.getPath();
                boolean looksLikeJson = (mimeType != null && mimeType.contains("json"))
                        || (uriPath != null && uriPath.toLowerCase(Locale.ROOT).endsWith(".json"));
                if (!looksLikeJson) {
                    // Read the first byte to check for JSON
                    try (InputStream probe = cr.openInputStream(uri)) {
                        if (probe == null || probe.read() != '{') {
                            throw new Exception("The selected file does not appear to be a valid Clover backup (expected a JSON file).");
                        }
                    }
                }

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
                String backupJson = sb.toString();
                if (!backupJson.startsWith("{")) {
                    throw new Exception("The selected file does not appear to be a valid Clover backup (expected a JSON file).");
                }
                Set<String> availableKeys = SettingsBackupRestore.getAvailableRestoreKeys(backupJson);
                showRestoreSelectionDialog(backupJson, availableKeys);
            } catch (Exception e) {
                Logger.e("MainSettingsController", "Restore failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : context.getString(R.string.settings_restore_failed);
                Toast.makeText(context, context.getString(R.string.settings_restore_failed) + ": " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // Show a dialog to select which settings to restore.
    private void showRestoreSelectionDialog(String backupJson, Set<String> availableKeys) {
        List<String> keyList = new ArrayList<>(availableKeys);
        List<String> displayNames = new ArrayList<>();
        boolean[] checkedItems = new boolean[keyList.size()];
        
        // Create display names and initialize all to checked
        for (int i = 0; i < keyList.size(); i++) {
            displayNames.add(SettingsBackupRestore.getKeyDisplayName(keyList.get(i)));
            checkedItems[i] = true;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.settings_restore_select);
        builder.setMultiChoiceItems(displayNames.toArray(new String[0]), checkedItems, (dialog, which, isChecked) -> {
            checkedItems[which] = isChecked;
        });
        
        builder.setPositiveButton(R.string.settings_restore_button, (dialog, which) -> {
            Set<String> selectedKeys = new HashSet<>();
            for (int i = 0; i < checkedItems.length; i++) {
                if (checkedItems[i]) {
                    selectedKeys.add(keyList.get(i));
                }
            }
            performRestore(backupJson, selectedKeys);
        });
        
        builder.setNegativeButton(android.R.string.cancel, null);
        
        // Add "Toggle all" button
        builder.setNeutralButton(R.string.settings_restore_toggle_all, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setBackgroundColor(getAttrColor(context, R.attr.backcolor));
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean allChecked = true;
                for (boolean checked : checkedItems) {
                    if (!checked) {
                        allChecked = false;
                        break;
                    }
                }

                boolean newCheckedState = !allChecked;
                ListView listView = dialog.getListView();
                for (int i = 0; i < checkedItems.length; i++) {
                    checkedItems[i] = newCheckedState;
                    if (listView != null) {
                        listView.setItemChecked(i, newCheckedState);
                    }
                }
            });
        });

        dialog.show();
    }
    
    // Perform the actual restore with selected keys.
    private void performRestore(String backupJson, Set<String> selectedKeys) {
        try {
            SettingsBackupRestore.importFull(databaseManager, AndroidUtils.getPreferences(), backupJson, selectedKeys);
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
    }

    // Unwraps context (e.g. ContextWrapper) to find StartActivity so restore can trigger restart.
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
        String[] abouts;
        try {
            abouts = context.getResources().getStringArray(R.array.extra_abouts);
        } catch (Exception e) {
            return;
        }

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
                    if ((++clickCount) % 4 == 0) {
                        boolean developer = !ChanSettings.developer.get();

                        ChanSettings.developer.set(developer);

                        Toast.makeText(context, (developer ? "Enabled" : "Disabled") +
                                " developer options", Toast.LENGTH_LONG).show();

                        developerView.view.setVisibility(developer ? View.VISIBLE : View.GONE);

                        final int PETAL_COUNT = 12;
                        final int BASE_DURATION_MS = 5000;
                        final Random rng = new Random();
                        final int containerW = navigationController.view.getWidth();
                        final int containerH = navigationController.view.getHeight();
                        final int petalSize = AndroidUtils.dp(36);

                        final int[] petalColors = {
                                0xFF64B5F6, // light blue
                                0xFFFFD54F, // gold
                                0xFF81C784  // light green
                        };

                        for (int p = 0; p < PETAL_COUNT; p++) {
                            final ImageView petal = new ImageView(context);
                            petal.setImageResource(R.drawable.ic_task_description);
                            petal.setColorFilter(new android.graphics.PorterDuffColorFilter(petalColors[p % petalColors.length], android.graphics.PorterDuff.Mode.SRC_IN));

                            float startX = rng.nextInt(Math.max(1, containerW));
                            float driftX = (rng.nextFloat() - 0.5f) * containerW * 0.4f;
                            float startY = -petalSize - rng.nextInt(Math.max(1, containerH / 3));
                            float endY = containerH + petalSize;
                            int duration = BASE_DURATION_MS + rng.nextInt(2500);

                            petal.setX(startX);
                            petal.setY(startY);
                            navigationController.view.addView(petal, new ViewGroup.LayoutParams(petalSize, petalSize));

                            AnimatorSet motionSet = new AnimatorSet();
                            motionSet.playTogether(
                                    ObjectAnimator.ofFloat(petal, View.Y, startY, endY),
                                    ObjectAnimator.ofFloat(petal, View.X, startX, startX + driftX),
                                    ObjectAnimator.ofFloat(petal, View.ROTATION, 0f, (rng.nextFloat() - 0.5f) * 540f)
                            );
                            motionSet.setDuration(duration);
                            motionSet.setInterpolator(new AccelerateInterpolator(1.1f));
                            motionSet.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (petal.getParent() instanceof ViewGroup) {
                                        ((ViewGroup) petal.getParent()).removeView(petal);
                                    }
                                }
                            });
                            motionSet.start();

                            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(petal, View.ALPHA, 1f, 0f);
                            fadeOut.setStartDelay((long) (duration * 0.55));
                            fadeOut.setDuration((long) (duration * 0.45));
                            fadeOut.start();
                        }
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
}
