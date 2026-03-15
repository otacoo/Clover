/*
 * BlueClover - 4chan browser https://github.com/nnuudev/BlueClover
 * Copyright (C) 2021 nnuudev
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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.di.NetModule;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.sites.chan4.Chan4;
import org.otacoo.chan.ui.helper.RefreshUIMessage;
import org.otacoo.chan.ui.toolbar.ToolbarMenu;
import org.otacoo.chan.ui.toolbar.ToolbarMenuItem;
import org.otacoo.chan.ui.view.AuthWebView;
import org.otacoo.chan.utils.Logger;

import de.greenrobot.event.EventBus;

public class EmailVerificationController extends Controller {
    private static final String TAG = "EmailVerificationController";

    private AuthWebView webView;
    private final String initialUrl;
    private String title = "Verification";
    private String[] requiredCookies;
    private boolean isFinished = false;
    private Site site;

    public EmailVerificationController(Context context) {
        this(context, "https://sys.4chan.org/signin");
    }

    public EmailVerificationController(Context context, String url) {
        super(context);
        this.initialUrl = url;
    }

    public EmailVerificationController(Context context, String url, String title) {
        super(context);
        this.initialUrl = url;
        this.title = title;
    }

    public void setRequiredCookies(String... cookies) {
        this.requiredCookies = cookies;
    }

    public void setSite(Site s) {
        this.site = s;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.title = title;
        navigation.swipeable = false;

        // Add overflow menu with Refresh and Open URL
        navigation.buildMenu()
                .withItem(R.drawable.ic_refresh_white_24dp, item -> {
                    if (webView != null) webView.reload();
                })
                .withOverflow()
                .withSubItem("Enter URL", item -> showUrlInputDialog())
                .build() // Completes MenuOverflowBuilder, returns MenuBuilder
                .build(); // Completes MenuBuilder, sets navigation.menu

        ToolbarMenuItem overflowItem = navigation.findItem(ToolbarMenu.OVERFLOW_ID);
        if (overflowItem != null) {
            overflowItem.overflowStyle = true;
        }

        // Set view synchronously so pushController doesn't crash with "Controller has no view".
        // The WebView will be added into this container after async cookie clearing.
        FrameLayout container = new FrameLayout(context);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        view = container;

        AuthWebView.runOnWebViewThread(this::setupWebView);
    }

    private void showUrlInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Enter URL");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(webView != null ? webView.getUrl() : initialUrl);
        builder.setView(input);

        builder.setPositiveButton("Go", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                if (!url.startsWith("http")) {
                    url = "https://" + url;
                }
                if (webView != null) {
                    webView.loadUrl(url);
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (!alive) return;
        clearCloudflareCookies();
        AuthWebView.runOnWebViewThread(this::createAndLoadWebView);
    }

    // Clear stale cookies before creating the WebView so the signin page gets a clean session.
    private void clearCloudflareCookies() {
        CookieManager cm = CookieManager.getInstance();
        // Comprehensive list of Cloudflare and bot-protection cookie names.
        String[] cfNames = {"cf_clearance", "__cf_bm", "__cfruid", "_cf_bm", "_tcm", "_cfuvid"};
        
        java.util.Set<String> apexes = new java.util.HashSet<>();
        apexes.add("4chan.org");
        apexes.add("4channel.org");

        if (initialUrl != null) {
            try {
                android.net.Uri uri = android.net.Uri.parse(initialUrl);
                String host = uri.getHost();
                if (host != null) {
                    if (host.endsWith("4chan.org")) {
                        apexes.add("4chan.org");
                    } else if (host.endsWith("4channel.org")) {
                        apexes.add("4channel.org");
                    } else {
                        // For other domains, try to find the apex.
                        String[] parts = host.split("\\.");
                        if (parts.length >= 2) {
                            apexes.add(parts[parts.length - 2] + "." + parts[parts.length - 1]);
                        } else {
                            apexes.add(host);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        for (String apex : apexes) {
            String url = "https://" + apex;
            for (String name : cfNames) {
                // Cloudflare usually uses the apex domain with a leading dot.
                cm.setCookie(url, name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=." + apex);
                cm.setCookie(url, name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=" + apex);
                cm.setCookie(url, name + "=; Max-Age=0; path=/");
                
                // Also attempt clearing for common subdomains explicitly.
                for (String sub : new String[]{"sys", "boards", "www"}) {
                    String subUrl = "https://" + sub + "." + apex;
                    cm.setCookie(subUrl, name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=." + sub + "." + apex);
                    cm.setCookie(subUrl, name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=" + sub + "." + apex);
                    cm.setCookie(subUrl, name + "=; Max-Age=0; path=/");
                }
            }
        }
        cm.flush();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createAndLoadWebView() {
        if (!alive) return;

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        // AuthWebView handles JavaScript, DOM storage, and basic cookie settings.
        webView = new AuthWebView(context);

        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                CookieManager.getInstance().flush();

                String cookies = CookieManager.getInstance().getCookie(url);
                Logger.d(TAG, "onPageFinished url=" + url + " cookies=" + cookies);

                boolean is8chan = initialUrl != null && (initialUrl.contains("8chan.moe") || initialUrl.contains("8chan.st") || initialUrl.contains("8chan.cc"));

                if (is8chan) {
                    checkCookies();
                } else if (url != null && url.contains("sys.4chan.org/signin") && url.contains("action=verify")) {
                    String pageTitle = view.getTitle();
                    if (pageTitle != null && (pageTitle.contains("Verified") || pageTitle.contains("Success"))) {
                        completeVerification();
                    }
                }
            }
        });

        // Re-inject site cookies after clearing (e.g. pass cookies, Cloudflare cookies)
        if (site != null) {
            site.requestModifier().modifyWebView(webView);
        }

        // Add WebView into the container that was set as view in onCreate
        if (view instanceof FrameLayout) {
            view.addView(webView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        webView.loadUrl(initialUrl);

        if (requiredCookies != null && requiredCookies.length > 0) {
            Toast.makeText(context, "Please solve the verification challenge to continue.", Toast.LENGTH_LONG).show();
        }
    }

    private void checkCookies() {
        if (isFinished || !alive) return;

        AuthWebView.runOnWebViewThread(() -> {
            String url = webView.getUrl();
            if (url == null) url = initialUrl;

            String cookies = CookieManager.getInstance().getCookie(url);
            boolean allPresent = requiredCookies != null && cookies != null;
            if (allPresent) {
                for (String name : requiredCookies) {
                    // Use prefix-match so e.g. "TOS" also matches "TOS20250413=..."
                    boolean found = java.util.regex.Pattern
                            .compile("\\b" + java.util.regex.Pattern.quote(name) + "\\w*=")
                            .matcher(cookies).find();
                    if (!found) {
                        allPresent = false;
                        break;
                    }
                }
            }

            if (allPresent) {
                completeVerification();
            } else {
                webView.postDelayed(this::checkCookies, 1000);
            }
        });
    }

    private void completeVerification() {
        if (isFinished) return;
        isFinished = true;

        CookieManager.getInstance().flush();

        // Sync WebView cookies into the java.net/OkHttp jar for 8chan.
        boolean is8chan = initialUrl != null && (initialUrl.contains("8chan.moe") || initialUrl.contains("8chan.st") || initialUrl.contains("8chan.cc"));
        if (is8chan) {
            NetModule.syncCookiesToJar(initialUrl);
        }

        Toast.makeText(context, "Verification successful!", Toast.LENGTH_SHORT).show();
        EventBus.getDefault().post(new RefreshUIMessage("Verification successful"));

        if (navigationController != null) {
            navigationController.popController();
        } else if (presentedByController != null) {
            stopPresenting();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // If this was a 4chan verification session, persist any 4chan_pass cookie that was
        // set by the site so it survives future CookieManager clears.
        if (site instanceof Chan4 chan4) {
            String sysCookies = CookieManager.getInstance().getCookie("https://sys.4chan.org");
            if (sysCookies != null) {
                for (String part : sysCookies.split(";\\s*")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("4chan_pass=")) {
                        String value = trimmed.substring("4chan_pass=".length());
                        if (!value.isEmpty()) {
                            chan4.getCookieStore().setChanPass(value);
                        }
                        break;
                    }
                }
            }
        }

        CookieManager.getInstance().flush();

        if (webView != null) {
            webView.destroy();
        }
    }
}
