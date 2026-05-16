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
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.inject.Inject;

public class DeveloperSettingsController extends Controller {
    private TextView summaryText;

    @Inject
    DatabaseManager databaseManager;

    public DeveloperSettingsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        inject(this);

        navigation.setTitle(R.string.settings_developer);

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        Button logsButton = new Button(context);
        logsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigationController.pushController(new LogsController(context));
            }
        });
        logsButton.setText(R.string.settings_open_logs);

        wrapper.addView(logsButton);

        Button crashButton = new Button(context);

        crashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                throw new RuntimeException("Debug crash");
            }
        });
        crashButton.setText("Crash the app");

        wrapper.addView(crashButton);

        Button clearWebStorageButton = new Button(context);
        clearWebStorageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebStorage.getInstance().deleteAllData();
                AndroidUtils.showThemedSnackbar(view, "WebView localStorage and sessionStorage cleared", Snackbar.LENGTH_LONG);
            }
        });
        clearWebStorageButton.setText("Clear WebView localStorage");
        wrapper.addView(clearWebStorageButton);

        Button clearCookiesButton = new Button(context);
        clearCookiesButton.setOnClickListener(v -> {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            AndroidUtils.showThemedSnackbar(view, "WebView cookies cleared", Snackbar.LENGTH_SHORT);
        });
        clearCookiesButton.setText("Clear WebView cookies");
        wrapper.addView(clearCookiesButton);

        summaryText = new TextView(context);
        summaryText.setPadding(0, dp(25), 0, 0);
        wrapper.addView(summaryText);

        setDbSummary();

        Button checkDbButton = new Button(context);
        checkDbButton.setText("Check database integrity");
        checkDbButton.setOnClickListener(v -> {
            checkDbButton.setEnabled(false);
            AndroidUtils.showThemedSnackbar(view, "Running integrity check…", Snackbar.LENGTH_SHORT);
            databaseManager.runTaskAsync(databaseManager.checkIntegrity(), report -> {
                checkDbButton.setEnabled(true);
                TextView tv = new TextView(context);
                int p = (int) (context.getResources().getDisplayMetrics().density * 12);
                tv.setPadding(p, p, p, p);
                tv.setText(report);
                tv.setTextSize(12f);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                ScrollView sv = new ScrollView(context);
                sv.addView(tv);
                AlertDialog dialog = new AlertDialog.Builder(context)
                        .setTitle("DB Integrity Report")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .show();
                dialog.getWindow().getDecorView().setBackgroundColor(getAttrColor(context, R.attr.backcolor));
            });
        });
        wrapper.addView(checkDbButton);

        Button resetDbButton = new Button(context);
        resetDbButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                databaseManager.reset();
                System.exit(0);
            }
        });
        resetDbButton.setText("Delete database");
        wrapper.addView(resetDbButton);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(wrapper);
        view = scrollView;
        view.setBackgroundColor(getAttrColor(context, R.attr.backcolor));
    }

    private void setDbSummary() {
        String dbSummary = "";
        dbSummary += "Database summary:\n";
        dbSummary += databaseManager.getSummary();
        summaryText.setText(dbSummary);
    }
}
