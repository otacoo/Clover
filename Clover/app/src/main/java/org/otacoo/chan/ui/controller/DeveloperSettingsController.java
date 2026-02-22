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
import android.widget.Toast;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.database.DatabaseManager;

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
                Toast.makeText(context, "WebView localStorage and sessionStorage cleared", Toast.LENGTH_LONG).show();
            }
        });
        clearWebStorageButton.setText("Clear WebView localStorage");
        wrapper.addView(clearWebStorageButton);

        Button clearCookiesButton = new Button(context);
        clearCookiesButton.setOnClickListener(v -> {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            Toast.makeText(context, "WebView cookies cleared", Toast.LENGTH_SHORT).show();
        });
        clearCookiesButton.setText("Clear WebView cookies");
        wrapper.addView(clearCookiesButton);

        Button viewCookiesButton = new Button(context);
        viewCookiesButton.setText("View/Edit 4chan Cookies");
        viewCookiesButton.setOnClickListener(v -> showCookieManagerDialog());
        wrapper.addView(viewCookiesButton);

        summaryText = new TextView(context);
        summaryText.setPadding(0, dp(25), 0, 0);
        wrapper.addView(summaryText);

        setDbSummary();

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

    private void showCookieManagerDialog() {
        final String[] DOMAINS = {"https://sys.4chan.org", "https://boards.4chan.org", "https://www.4chan.org"};
        CookieManager cm = CookieManager.getInstance();
        cm.flush();

        // Collect all cookies from 4chan domains; later domains overwrite earlier for same name.
        java.util.LinkedHashMap<String, String> cookieMap = new java.util.LinkedHashMap<>();
        for (String domain : DOMAINS) {
            String raw = cm.getCookie(domain);
            if (raw == null || raw.isEmpty()) continue;
            for (String part : raw.split(";\\s*")) {
                int eq = part.indexOf('=');
                String name = (eq >= 0 ? part.substring(0, eq) : part).trim();
                String val  = eq >= 0 ? part.substring(eq + 1).trim() : "";
                if (!name.isEmpty()) cookieMap.put(name, val);
            }
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        if (cookieMap.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No cookies found for 4chan domains.");
            root.addView(empty);
        } else {
            for (java.util.Map.Entry<String, String> entry : cookieMap.entrySet()) {
                final String cookieName = entry.getKey();
                final String cookieVal  = entry.getValue();

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(4), 0, dp(4));

                // Name + value column
                LinearLayout info = new LinearLayout(context);
                info.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                info.setLayoutParams(infoLp);

                TextView nameView = new TextView(context);
                nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                nameView.setTextSize(13f);
                nameView.setText(cookieName);
                info.addView(nameView);

                TextView valView = new TextView(context);
                valView.setTextSize(11f);
                String displayVal = cookieVal.length() > 60
                        ? cookieVal.substring(0, 60) + "…" : cookieVal;
                valView.setText(displayVal.isEmpty() ? "(empty)" : displayVal);
                info.addView(valView);

                row.addView(info);

                // Edit button
                Button editBtn = new Button(context);
                editBtn.setText("Edit");
                editBtn.setTextSize(11f);
                editBtn.setOnClickListener(ev -> {
                    EditText et = new EditText(context);
                    et.setText(cookieVal);
                    et.setSelection(et.getText().length());
                    et.setSingleLine(false);
                    et.setMinLines(2);
                    int etPad = dp(12);
                    et.setPadding(etPad, etPad, etPad, etPad);
                    new AlertDialog.Builder(context)
                            .setTitle("Edit \"" + cookieName + "\"")
                            .setView(et)
                            .setPositiveButton("Save", (dlg, which) -> {
                                String newVal = et.getText().toString();
                                for (String domain : DOMAINS)
                                    cm.setCookie(domain, cookieName + "=" + newVal);
                                cm.flush();
                                Toast.makeText(context, "Saved " + cookieName, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
                row.addView(editBtn);

                // Delete button
                Button delBtn = new Button(context);
                delBtn.setText("Del");
                delBtn.setTextSize(11f);
                delBtn.setOnClickListener(ev -> {
                    new AlertDialog.Builder(context)
                            .setTitle("Delete \"" + cookieName + "\"?")
                            .setPositiveButton("Delete", (dlg, which) -> {
                                String expired = cookieName + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
                                for (String domain : DOMAINS)
                                    cm.setCookie(domain, expired);
                                cm.flush();
                                Toast.makeText(context, "Deleted " + cookieName, Toast.LENGTH_SHORT).show();
                                showCookieManagerDialog(); // refresh
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
                row.addView(delBtn);

                root.addView(row);

                // Divider
                View divider = new View(context);
                divider.setBackgroundColor(0x22000000);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                root.addView(divider);
            }
        }

        ScrollView sv = new ScrollView(context);
        sv.addView(root);

        new AlertDialog.Builder(context)
                .setTitle("4chan Cookies (" + cookieMap.size() + " entries)")
                .setView(sv)
                .setPositiveButton("Refresh", (dlg, which) -> showCookieManagerDialog())
                .setNegativeButton("Close", null)
                .show();
    }
}
