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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

import org.otacoo.chan.R;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.ui.controller.StyledToolbarNavigationController;
import org.otacoo.chan.ui.view.ViewPagerAdapter;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CookieManagerController extends StyledToolbarNavigationController implements View.OnClickListener {
    private SiteRepository siteRepository;
    private List<Site> sites;
    private ViewPager pager;
    private LinearLayout tabContainer;
    private Button addCookie;
    private Button clearAll;
    private CookieAdapter adapter;

    public CookieManagerController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        siteRepository = injector().instance(SiteRepository.class);
        sites = siteRepository.all().getAllInOrder();

        navigation.setTitle(R.string.setting_cookies_view_edit);
        view = inflateRes(R.layout.controller_cookie_manager);

        tabContainer = view.findViewById(R.id.tab_container);
        pager = view.findViewById(R.id.pager);
        addCookie = view.findViewById(R.id.add_cookie);
        addCookie.setOnClickListener(this);
        clearAll = view.findViewById(R.id.clear_all);
        clearAll.setOnClickListener(this);

        adapter = new CookieAdapter();
        pager.setAdapter(adapter);

        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateTabs(position);
            }
        });

        setupTabs();
        updateTabs(0);
    }

    private List<String> getDomainsForUrl(String url) {
        List<String> domains = new ArrayList<>();
        android.net.Uri uri = android.net.Uri.parse(url);
        String host = uri.getHost();
        if (host != null) {
            domains.add("https://" + host);
            if (host.startsWith("www.")) {
                domains.add("https://" + host.substring(4));
            } else {
                domains.add("https://www." + host);
            }
            // Add common subdomains for specific sites if needed
            if (host.contains("4chan.org")) {
                domains.add("https://boards.4chan.org");
                domains.add("https://sys.4chan.org");
            }
            if (host.contains("4channel.org")) {
                domains.add("https://boards.4channel.org");
                domains.add("https://sys.4channel.org");
            }
        }
        return domains;
    }

    private void setupTabs() {
        tabContainer.removeAllViews();
        for (int i = 0; i < sites.size(); i++) {
            final int index = i;
            Site site = sites.get(i);

            TextView tab = new TextView(context);
            tab.setText(site.name());
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(AndroidUtils.dp(16), 0, AndroidUtils.dp(16), 0);
            tab.setAllCaps(true);
            tab.setTextSize(14);
            tab.setTypeface(null, android.graphics.Typeface.BOLD);
            tab.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tab.setFocusable(true);
            tab.setClickable(true);
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            tab.setBackgroundResource(outValue.resourceId);

            tab.setOnClickListener(v -> pager.setCurrentItem(index));

            tabContainer.addView(tab);

            if (i < sites.size() - 1) {
                View separator = new View(context);
                separator.setBackgroundColor(AndroidUtils.getAttrColor(context, R.attr.divider_color));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtils.dp(1), AndroidUtils.dp(24));
                lp.gravity = Gravity.CENTER_VERTICAL;
                separator.setLayoutParams(lp);
                tabContainer.addView(separator);
            }
        }
    }

    private void updateTabs(int selectedIndex) {
        int siteIndex = 0;
        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            View child = tabContainer.getChildAt(i);
            if (!(child instanceof TextView)) continue;

            TextView tab = (TextView) child;
            if (siteIndex == selectedIndex) {
                tab.setTextColor(AndroidUtils.getAttrColor(context, R.attr.text_color_primary));
                tab.setAlpha(1.0f);
                // Center the selected tab in the scroll view
                final View scroll = view.findViewById(R.id.tab_scroll);
                final View finalTab = tab;
                scroll.post(() -> {
                    int scrollX = (finalTab.getLeft() - (scroll.getWidth() / 2)) + (finalTab.getWidth() / 2);
                    scroll.scrollTo(scrollX, 0);
                });
            } else {
                tab.setTextColor(AndroidUtils.getAttrColor(context, R.attr.text_color_secondary));
                tab.setAlpha(0.6f);
            }
            siteIndex++;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == clearAll) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.setting_confirm_clear_cookies)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        CookieSyncManager.createInstance(context);
                        CookieManager cookieManager = CookieManager.getInstance();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cookieManager.removeAllCookies(null);
                        } else {
                            cookieManager.removeAllCookie();
                        }
                        Toast.makeText(context, R.string.setting_cleared_saved_cookies, Toast.LENGTH_LONG).show();
                        adapter.notifyDataSetChanged();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else if (v == addCookie) {
            showAddCookieDialog();
        }
    }

    private void showAddCookieDialog() {
        Site site = sites.get(pager.getCurrentItem());
        String rootUrl = site.endpoints().root().toString();
        List<String> domains = getDomainsForUrl(rootUrl);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtils.dp(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView siteLabel = new TextView(context);
        siteLabel.setText("Adding cookie for: " + site.name());
        siteLabel.setPadding(0, 0, 0, pad / 2);
        layout.addView(siteLabel);

        EditText nameEt = new EditText(context);
        nameEt.setHint("Name");
        layout.addView(nameEt);

        EditText valEt = new EditText(context);
        valEt.setHint("Value");
        layout.addView(valEt);

        new AlertDialog.Builder(context)
                .setTitle("Add Cookie")
                .setView(layout)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String name = nameEt.getText().toString().trim();
                    String val = valEt.getText().toString().trim();
                    if (!name.isEmpty()) {
                        CookieManager cm = CookieManager.getInstance();
                        for (String domain : domains) {
                            cm.setCookie(domain, name + "=" + val);
                        }
                        cm.flush();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private class CookieAdapter extends ViewPagerAdapter {
        @Override
        public int getCount() {
            return sites.size();
        }

        @Override
        public View getView(int position, ViewGroup parent) {
            Site site = sites.get(position);
            String rootUrl = site.endpoints().root().toString();
            List<String> domains = getDomainsForUrl(rootUrl);

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.flush();

            Map<String, String> cookies = new LinkedHashMap<>();
            for (String domain : domains) {
                String raw = cookieManager.getCookie(domain);
                if (raw != null) {
                    for (String part : raw.split(";\\s*")) {
                        int eq = part.indexOf('=');
                        String name = (eq >= 0 ? part.substring(0, eq) : part).trim();
                        String val = eq >= 0 ? part.substring(eq + 1).trim() : "";
                        if (!name.isEmpty()) cookies.put(name, val);
                    }
                }
            }

            if (cookies.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText("No cookies found for " + site.name());
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, AndroidUtils.dp(32), 0, 0);
                return empty;
            }

            ScrollView scrollView = new ScrollView(context);
            LinearLayout list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(list);

            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String nameStr = entry.getKey();
                String valStr = entry.getValue();

                View item = LayoutInflater.from(context).inflate(R.layout.cookie_item, list, false);
                TextView name = item.findViewById(R.id.cookie_name);
                TextView value = item.findViewById(R.id.cookie_value);
                ImageView deleteBtn = item.findViewById(R.id.delete_cookie);

                name.setText(nameStr);
                value.setText(valStr);

                item.setOnClickListener(v -> {
                    showEditCookieDialog(site.name(), domains, nameStr, valStr);
                });

                deleteBtn.setOnClickListener(v -> {
                    new AlertDialog.Builder(context)
                            .setTitle("Delete " + nameStr + "?")
                            .setPositiveButton(R.string.ok, (d, w) -> {
                                for (String domain : domains) {
                                    cookieManager.setCookie(domain, nameStr + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                                }
                                cookieManager.flush();
                                notifyDataSetChanged();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });

                list.addView(item);
            }

            return scrollView;
        }

        private void showEditCookieDialog(String siteName, List<String> domains, String name, String oldVal) {
            android.widget.EditText et = new android.widget.EditText(context);
            et.setText(oldVal);
            new AlertDialog.Builder(context)
                    .setTitle("Edit " + name + " (" + siteName + ")")
                    .setView(et)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String newVal = et.getText().toString();
                        CookieManager cm = CookieManager.getInstance();
                        for (String domain : domains) {
                            cm.setCookie(domain, name + "=" + newVal);
                        }
                        cm.flush();
                        notifyDataSetChanged();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }
    }
}
