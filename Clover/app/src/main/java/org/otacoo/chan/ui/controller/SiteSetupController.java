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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.otacoo.chan.R;
import org.otacoo.chan.core.presenter.SiteSetupPresenter;
import org.otacoo.chan.core.settings.OptionsSetting;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteSetting;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.ListSettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import okhttp3.HttpUrl;

public class SiteSetupController extends SettingsController implements SiteSetupPresenter.Callback {
    @Inject
    SiteSetupPresenter presenter;

    private Site site;
    private LinkSettingView boardsLink;
    private LinkSettingView loginLink;

    public SiteSetupController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        // Navigation
        navigation.setTitle(R.string.settings_screen);
        navigation.title = context.getString(R.string.setup_site_title, site.name());

        // View binding
        view = inflateRes(R.layout.settings_layout);
        content = view.findViewById(R.id.scrollview_content);

        // Preferences
        populatePreferences();

        // Presenter
        presenter.create(this, site);

        buildPreferences();
    }

    public void setSite(Site site) {
        this.site = site;
    }

    @Override
    public void onShow() {
        super.onShow();
        presenter.show();
    }

    @Override
    public void setIsLoggedIn(boolean isLoggedIn) {
        String text = context.getString(isLoggedIn ?
                R.string.setup_site_login_description_enabled :
                R.string.setup_site_login_description_disabled);
        loginLink.setDescription(text);
    }
    @Override
    public void setBoardCount(int boardCount) {
        String boardsString = context.getResources().getQuantityString(
                R.plurals.board, boardCount, boardCount);
        String descriptionText = context.getString(
                R.string.setup_site_boards_description, boardsString);
        boardsLink.setDescription(descriptionText);
    }

    @Override
    public void showSettings(List<SiteSetting> settings) {
        SettingsGroup group = new SettingsGroup("Additional settings");

        for (SiteSetting setting : settings) {
            if (setting.type == SiteSetting.Type.OPTIONS) {

                // Turn the SiteSetting for a list of options into a proper setting with a
                // name and a list of options, both given in the SiteSetting.
                OptionsSetting optionsSetting = (OptionsSetting) setting.setting;

                List<ListSettingView.Item<Enum>> items = new ArrayList<>();
                Enum[] settingItems = optionsSetting.getItems();
                for (int i = 0; i < setting.optionNames.size(); i++) {
                    String name = setting.optionNames.get(i);
                    Enum anEnum = settingItems[i];
                    items.add(new ListSettingView.Item<>(name, anEnum));
                }

                ListSettingView<?> v = getListSettingView(setting, optionsSetting, items);

                group.add(v);
            }
        }

        groups.add(group);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private ListSettingView<?> getListSettingView(
            SiteSetting setting,
            OptionsSetting optionsSetting,
            List<ListSettingView.Item<Enum>> items) {
        // we know it's an enum
        return (ListSettingView<?>) new ListSettingView(this,
                optionsSetting, setting.name, items);
    }

    @Override
    public void showLogin() {
        SettingsGroup login = new SettingsGroup(R.string.setup_site_group_login);
        loginLink = new LinkSettingView(
                this,
                context.getString(R.string.setup_site_login),
                "",
                v -> {
                    if (site.name().equals("8chan.moe") || site.name().equals("8chan")) {
                        HttpUrl loginUrl = site.endpoints().login();
                        EmailVerificationController webController = new EmailVerificationController(context, loginUrl.toString(), site.name() + " Verification");
                        webController.setRequiredCookies("TOS20250418", "POW_TOKEN");
                        navigationController.pushController(webController);
                    } else {
                        LoginController loginController = new LoginController(context);
                        loginController.setSite(site);
                        navigationController.pushController(loginController);
                    }
                }
        );
        login.add(loginLink);
        groups.add(login);
        
        // Add email verification for 4chan
        if (site.name().equals("4chan")) {
            SettingsGroup verification = new SettingsGroup("Verification");
            
            LinkSettingView verifyEmailLink = new LinkSettingView(
                    this,
                    "Verify your email",
                    "Open email verification page",
                    v -> {
                        EmailVerificationController verificationController = new EmailVerificationController(context);
                        navigationController.pushController(verificationController);
                    }
            );
            verification.add(verifyEmailLink);
            
            LinkSettingView enterTokenLink = new LinkSettingView(
                    this,
                    "Enter verification token",
                    "Paste verification link or token",
                    v -> showVerificationTokenDialog()
            );
            verification.add(enterTokenLink);
            
            groups.add(verification);
        }
    }

    private void populatePreferences() {
        SettingsGroup general = new SettingsGroup(R.string.setup_site_group_general);

        boardsLink = new LinkSettingView(
                this,
                context.getString(R.string.setup_site_boards),
                "",
                v -> {
                    BoardSetupController boardSetupController = new BoardSetupController(context);
                    boardSetupController.setSite(site);
                    navigationController.pushController(boardSetupController);
                });
        general.add(boardsLink);

        groups.add(general);
    }
    
    private void showVerificationTokenDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Email Verification");
        builder.setMessage("Paste the verification link or token from your email:");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://sys.4chan.org/signin?action=verify&tkn=...");
        builder.setView(input);

        builder.setPositiveButton("Verify", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    return;
                }
                
                String url;
                // Check if it's a full URL or just a token
                if (text.startsWith("http")) {
                    // Full URL
                    if (text.contains("sys.4chan.org/signin") && text.contains("action=verify") && text.contains("tkn=")) {
                        url = text;
                    } else {
                        Toast.makeText(context, "Invalid verification link", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    // Just a token - construct the URL
                    url = "https://sys.4chan.org/signin?action=verify&tkn=" + text;
                }
                
                EmailVerificationController verificationController = new EmailVerificationController(context, url);
                navigationController.pushController(verificationController);
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }
}
