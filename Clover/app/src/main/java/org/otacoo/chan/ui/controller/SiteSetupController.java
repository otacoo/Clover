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
import android.view.Gravity;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.presenter.SiteSetupPresenter;
import org.otacoo.chan.core.settings.BooleanSetting;
import org.otacoo.chan.core.settings.OptionsSetting;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.sites.chan4.Chan4;
import org.otacoo.chan.core.site.SiteSetting;
import org.otacoo.chan.ui.settings.BooleanSettingView;
import org.otacoo.chan.ui.settings.LinkSettingView;
import org.otacoo.chan.ui.settings.ListSettingView;
import org.otacoo.chan.ui.settings.SettingsController;
import org.otacoo.chan.ui.settings.SettingsGroup;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import okhttp3.HttpUrl;

public class SiteSetupController extends SettingsController implements SiteSetupPresenter.Callback {
    @Inject
    SiteSetupPresenter presenter;

    private Site site;
    private LinkSettingView boardsLink;
    private LinkSettingView loginLink;
    private LinkSettingView verificationLink;
    private LinkSettingView passCookieLink;

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
        // Re-read the live 4chan_pass value from CookieManager every time this screen is shown.
        // This picks up cookies set by "Enter verification token", the Cookie Manager, or any
        // other source and keeps the setting and the UI description in sync with THE truth.
        if (site != null && "4chan".equals(site.name()) && passCookieLink != null) {
            Chan4 chan4 = (Chan4) site;
            String currentPassCookie = chan4.getCookieStore().getChanPass();
            passCookieLink.setDescription(currentPassCookie.isEmpty()
                    ? context.getString(R.string.setup_site_4chan_pass_cookie_not_set)
                    : context.getString(R.string.setup_site_4chan_pass_cookie_set));
        }
    }

    private static String extractChanPassValue(String cookieHeader) {
        if (cookieHeader == null) return "";
        for (String part : cookieHeader.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if ("4chan_pass".equals(part.substring(0, eq).trim())) {
                return part.substring(eq + 1).trim();
            }
        }
        return "";
    }

    @Override
    public void setIsLoggedIn(boolean isLoggedIn) {
        if (site.name().equals("8chan.moe") || site.name().equals("8chan")) {
            if (verificationLink != null) {
                verificationLink.setDescription(context.getString(isLoggedIn ?
                        R.string.setup_site_login_description_valid :
                        R.string.setup_site_login_description_disabled));
            }
        } else {
            loginLink.setDescription(context.getString(isLoggedIn ?
                    R.string.setup_site_login_description_enabled :
                    R.string.setup_site_login_description_disabled));
        }
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
    @SuppressWarnings("rawtypes")
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
            } else if (setting.type == SiteSetting.Type.BOOLEAN) {
                group.add(new BooleanSettingView(this, (BooleanSetting) setting.setting, setting.name, setting.description));
            }
        }

        groups.add(group);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
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
        int loginGroupTitle = R.string.setup_site_group_login;

        if (site.name().equals("4chan")) {
            loginGroupTitle = R.string.setup_site_group_login_4chan;
        }

        SettingsGroup login = new SettingsGroup(loginGroupTitle);

        if (site.name().equals("8chan.moe") || site.name().equals("8chan")) {
            verificationLink = new LinkSettingView(
                    this,
                    context.getString(R.string.setup_site_login_8chan),
                    "",
                    v -> {
                        HttpUrl loginUrl = site.endpoints().root();
                        EmailVerificationController webController = new EmailVerificationController(context, loginUrl.toString(), site.name() + " Verification");
                        webController.setSite(site);
                        webController.setRequiredCookies("TOS", "POW_TOKEN", "POW_ID");
                        navigationController.pushController(webController);
                    }
            );
            login.add(verificationLink);
        } else {
            loginLink = new LinkSettingView(
                    this,
                    context.getString(R.string.setup_site_login),
                    "",
                    v -> {
                        LoginController loginController = new LoginController(context);
                        loginController.setSite(site);
                        navigationController.pushController(loginController);
                    }
            );
            login.add(loginLink);
        }

        groups.add(login);
        
        // Add email verification for 4chan
        if (site.name().equals("4chan")) {
            Chan4 chan4site = (Chan4) site;
            SettingsGroup verification = new SettingsGroup(context.getString(R.string.setup_site_4chan_verification_group));

            LinkSettingView verifyEmailLink = new LinkSettingView(
                    this,
                    context.getString(R.string.setup_site_4chan_verify_email),
                    context.getString(R.string.setup_site_4chan_verify_email_description),
                    v -> new AlertDialog.Builder(context)
                            .setTitle(R.string.setup_site_4chan_verify_email_warning_title)
                            .setMessage(R.string.setup_site_4chan_verify_email_warning_body)
                            .setPositiveButton(R.string.setup_site_4chan_verify_email_warning_open, (d, w) -> {
                                EmailVerificationController verificationController = new EmailVerificationController(context);
                                verificationController.setSite(site);
                                navigationController.pushController(verificationController);
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show()
            );
            verification.add(verifyEmailLink);

            LinkSettingView enterTokenLink = new LinkSettingView(
                    this,
                    context.getString(R.string.setup_site_4chan_enter_token),
                    context.getString(R.string.setup_site_4chan_enter_token_description),
                    v -> showVerificationTokenDialog()
            );
            verification.add(enterTokenLink);

            String currentPassCookie = chan4site.getCookieStore().getChanPass();
            passCookieLink = new LinkSettingView(
                    this,
                    context.getString(R.string.setup_site_4chan_pass_cookie_name),
                    currentPassCookie.isEmpty()
                            ? context.getString(R.string.setup_site_4chan_pass_cookie_not_set)
                            : context.getString(R.string.setup_site_4chan_pass_cookie_set),
                    v -> showPassCookieDialog()
            );
            verification.add(passCookieLink);

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
                    String verifUrl = site.actions().verificationUrl();
                    if (verifUrl != null) {
                        EmailVerificationController webController =
                                new EmailVerificationController(context, verifUrl, site.name() + " Verification");
                        webController.setSite(site);
                        webController.setRequiredCookies("TOS", "POW_TOKEN", "POW_ID");
                        navigationController.pushController(webController);
                    } else {
                        BoardSetupController boardSetupController = new BoardSetupController(context);
                        boardSetupController.setSite(site);
                        navigationController.pushController(boardSetupController);
                    }
                });
        general.add(boardsLink);

        groups.add(general);
    }
    
    private void showPassCookieDialog() {
        Chan4 chan4 = (Chan4) site;
        String currentValue = chan4.getCookieStore().getChanPass();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.setup_site_4chan_pass_cookie_dialog_title);
        builder.setMessage(R.string.setup_site_4chan_pass_cookie_dialog_message);

        final EditText input = new EditText(context);
        // Force wrap_content and proper expansion for long tokens.
        // We use TYPE_TEXT_FLAG_MULTI_LINE to ensure the field grows and wraps correctly.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setGravity(Gravity.TOP);
        input.setMinLines(3);
        input.setMaxLines(10);
        input.setHint(R.string.setup_site_4chan_pass_cookie_hint);
        if (!currentValue.isEmpty()) {
            input.setText(currentValue);
            input.setSelection(currentValue.length());
        }
        builder.setView(input);

        builder.setPositiveButton(R.string.save, (dialog, which) -> {
            String newValue = input.getText().toString().trim();
            chan4.getCookieStore().setChanPass(newValue);
            if (passCookieLink != null) {
                passCookieLink.setDescription(context.getString(newValue.isEmpty()
                        ? R.string.setup_site_4chan_pass_cookie_not_set
                        : R.string.setup_site_4chan_pass_cookie_set));
            }
            AndroidUtils.showThemedSnackbar(view,
                    newValue.isEmpty()
                            ? R.string.setup_site_4chan_pass_cookie_cleared
                            : R.string.setup_site_4chan_pass_cookie_saved,
                    Snackbar.LENGTH_SHORT);
        });

        builder.setNeutralButton(R.string.clear, (dialog, which) -> {
            chan4.getCookieStore().setChanPass("");
            if (passCookieLink != null) {
                passCookieLink.setDescription(context.getString(R.string.setup_site_4chan_pass_cookie_not_set));
            }
            AndroidUtils.showThemedSnackbar(view, R.string.setup_site_4chan_pass_cookie_cleared, Snackbar.LENGTH_SHORT);
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
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
                        AndroidUtils.showThemedSnackbar(view, "Invalid verification link", Snackbar.LENGTH_SHORT);
                        return;
                    }
                } else {
                    // Just a token - construct the URL
                    url = "https://sys.4chan.org/signin?action=verify&tkn=" + text;
                }
                
                EmailVerificationController verificationController = new EmailVerificationController(context, url);
                verificationController.setSite(site);
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
