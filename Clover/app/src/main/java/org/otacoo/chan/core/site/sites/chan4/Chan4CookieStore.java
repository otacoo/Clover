/*
 * Clover - 4chan browser - https://github.com/otacoo/Clover/
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo
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
package org.otacoo.chan.core.site.sites.chan4;

import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebView;

import org.otacoo.chan.core.settings.SettingProvider;
import org.otacoo.chan.core.settings.SharedPreferencesSettingProvider;
import org.otacoo.chan.core.settings.StringSetting;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * This is the single source of truth for 4chan pass cookies.
 *
 * passId   ("preference_pass_id")           - the PAID 4chan pass
 * chanPass ("preference_4chan_pass_cookie") - the EMAIL VERIFICATION 4chan_pass device token
 *
 * Bot-protection cookies (cf_clearance, _tcm, __cf_bm) remain the
 * responsibility of android.webkit.CookieManager (WebView) and are only read here, never owned.
 *
 * 8chan and Lynxchan use java.net.CookieManager / AppCookieJar - entirely separate,
 * not touched by this file.
 */
public class Chan4CookieStore {

    private static final String[] PASS_DOMAINS = {
            "https://4chan.org/", "https://www.4chan.org/", "https://boards.4chan.org/", "https://sys.4chan.org/",
            "https://4channel.org/", "https://www.4channel.org/", "https://boards.4channel.org/", "https://sys.4channel.org/"
    };

    private static final String[] SESSION_DOMAINS = {
            "https://sys.4chan.org",
            "https://boards.4chan.org",
            "https://www.4chan.org"
    };

    private final StringSetting passId;

    public Chan4CookieStore() {
        SettingProvider p = new SharedPreferencesSettingProvider(AndroidUtils.getPreferences());
        passId = new StringSetting(p, "preference_pass_id", "");
    }

    // Returns the passId StringSetting for external callers that need direct access (e.g. login flow). */
    public StringSetting getPassId() {
        return passId;
    }

    // Returns the chanPass string value directly from CookieManager for external callers (e.g. SiteSetupController). */
    public String getChanPass() {
        CookieManager cm = CookieManager.getInstance();
        String cookieHeader = cm.getCookie("https://sys.4chan.org");
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

    // Returns true if either pass token is present.
    // Used only for cookie-building decisions (getCookieHeader, syncToWebView) where
    // both tokens should be injected. Do NOT use this to drive "Logged in" UI or
    // postRequiresAuthentication - use Chan4.actions.isLoggedIn() (pass_id only) for those.
    public boolean isPassAuthenticated() {
        return !passId.get().isEmpty() || !getChanPass().isEmpty();
    }

    // Returns true if pass_id (non-zero) or pass_enabled=1 is present in the WebView cookie store.
    public boolean isPassInWebViewCookies() {
        CookieManager cm = CookieManager.getInstance();
        for (String domain : PASS_DOMAINS) {
            String cookies = cm.getCookie(domain);
            if (cookies == null) continue;
            for (String part : cookies.split(";")) {
                String trimmed = part.trim();
                if (trimmed.equals("pass_enabled=1")) return true;
                if (trimmed.startsWith("pass_id=")) {
                    String val = trimmed.substring("pass_id=".length()).trim();
                    if (!val.isEmpty() && !val.equals("0")) return true;
                }
            }
        }
        return false;
    }

    // Sets a new pass_id value, persists to SharedPrefs, and immediately propagates to the
    // WebView store so subsequent WebView-based captcha loads recognise the device.
    // Passing an empty string (logout) expires pass_id and pass_enabled in the WebView.
    public void setPassId(String value) {
        passId.set(value);
        CookieManager cm = CookieManager.getInstance();
        if (value.isEmpty()) {
            expirePassSessionCookies(cm);
        } else {
            for (String domain : PASS_DOMAINS) {
                cm.setCookie(domain, "pass_enabled=1;");
                cm.setCookie(domain, "pass_id=" + value + ";");
            }
        }
        cm.flush();
    }

    // Sets a new 4chan_pass value directly to the WebView store for all 4chan domains.
    public void setChanPass(String value) {
        CookieManager cm = CookieManager.getInstance();
        if (value.isEmpty()) {
            String expired = "4chan_pass=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
            for (String domain : PASS_DOMAINS) {
                cm.setCookie(domain, expired);
                android.net.Uri uri = android.net.Uri.parse(domain);
                String host = uri.getHost();
                if (host != null) {
                    if (host.startsWith("www.")) {
                        host = host.substring(4);
                    }
                    cm.setCookie(domain, expired + "; Domain=" + host);
                    cm.setCookie(domain, expired + "; Domain=." + host);
                }
            }
        } else {
            String cookie = "4chan_pass=" + value + "; expires=Fri, 31 Dec 2038 23:59:59 GMT; path=/";
            for (String domain : PASS_DOMAINS) cm.setCookie(domain, cookie);
        }
        cm.flush();
    }

    // This seeds the WebView store from SharedPrefs on every app start.
    // Restores both tokens so any early WebView request (before the first modifyWebView() call) already
    // carries the correct pass identity:
    // pass_id / pass_enabled  - paid 4chan Pass
    // 4chan_pass              - email verification token
    public void init() {
        CookieManager cm = CookieManager.getInstance();
        boolean flushed = false;

        String id = passId.get();
        if (!id.isEmpty()) {
            for (String domain : PASS_DOMAINS) {
                cm.setCookie(domain, "pass_enabled=1;");
                cm.setCookie(domain, "pass_id=" + id + ";");
            }
            flushed = true;
        }

        if (flushed) cm.flush();
    }

    // Builds the full cookie: header value for OkHttp requests to 4chan.
    // Session cookies are read from the WebView store pass identity is appended
    // directly from SharedPrefs so it is present even after a WebView cookie clear.
    public String getCookieHeader(String url) {
        Set<String> parts = new LinkedHashSet<>();
        CookieManager cm = CookieManager.getInstance();

        // Session cookies for the exact request URL first
        String requestCookies = cm.getCookie(url);
        if (requestCookies != null && !requestCookies.isEmpty()) {
            parts.addAll(Arrays.asList(requestCookies.split(";\\s*")));
        }
        // Aggregate across known 4chan domains
        for (String domain : SESSION_DOMAINS) {
            String cookies = cm.getCookie(domain);
            if (cookies != null && !cookies.isEmpty()) {
                parts.addAll(Arrays.asList(cookies.split(";\\s*")));
            }
        }

        // Pass identity always from SharedPrefs
        if (isPassAuthenticated()) {
            String id = passId.get();
            if (!id.isEmpty()) {
                parts.add("pass_id=" + id);
                parts.add("pass_enabled=1");
            }
            String pass = getChanPass();
            if (!pass.isEmpty()) {
                parts.add("4chan_pass=" + pass);
            }
        }

        return parts.isEmpty() ? null : TextUtils.join("; ", parts);
    }

    // Like getCookieHeader but strips pass_id and pass_enabled cookies.
    // Used when the user wants to skip their 4chan pass for a single post.
    public String getCookieHeaderWithoutPass(String url) {
        Set<String> parts = new LinkedHashSet<>();
        CookieManager cm = CookieManager.getInstance();

        String requestCookies = cm.getCookie(url);
        if (requestCookies != null && !requestCookies.isEmpty()) {
            parts.addAll(Arrays.asList(requestCookies.split(";\\s*")));
        }
        for (String domain : SESSION_DOMAINS) {
            String cookies = cm.getCookie(domain);
            if (cookies != null && !cookies.isEmpty()) {
                parts.addAll(Arrays.asList(cookies.split(";\\s*")));
            }
        }

        parts.removeIf(c -> {
            String trimmed = c.trim();
            return trimmed.startsWith("pass_id=")
                    || trimmed.startsWith("pass_enabled=");
        });

        return parts.isEmpty() ? null : TextUtils.join("; ", parts);
    }

    // Injects 4chan pass cookies from SharedPrefs into the given WebView so that 4chan's captcha
    // and report pages receive the correct pass identity for this device.
    public void syncToWebView(WebView webView) {
        CookieManager cm = CookieManager.getInstance();

        if (isPassAuthenticated()) {
            String id = passId.get();
            if (!id.isEmpty()) {
                cm.setCookie("https://sys.4chan.org/", "pass_enabled=1;");
                cm.setCookie("https://sys.4chan.org/", "pass_id=" + id + ";");
                cm.setCookie("https://boards.4chan.org/", "pass_enabled=1;");
                cm.setCookie("https://boards.4chan.org/", "pass_id=" + id + ";");
            } else {
                expirePassSessionCookies(cm);
            }
        } else {
            // Not authenticated - only expire if no existing 4chan_pass in WebView
            String sysC = cm.getCookie("https://sys.4chan.org");
            if (sysC == null || !sysC.contains("4chan_pass=")) {
                expirePassSessionCookies(cm);
            }
        }

        cm.flush();
    }

    // Forwards cookies to the WebView store and updates passId (paid) in SharedPrefs if 4chan rotates it
    public void onServerCookies(List<String> setCookieHeaders) {
        CookieManager cm = CookieManager.getInstance();
        for (String header : setCookieHeaders) {
            String val = header.split(";")[0].trim();
            for (String domain : PASS_DOMAINS) {
                cm.setCookie(domain, val);
            }
            if (val.startsWith("pass_id=")) {
                String freshId = val.substring("pass_id=".length());
                if (!freshId.isEmpty() && !freshId.equals("0")) {
                    passId.set(freshId);
                }
            }
        }
        cm.flush();
    }

    private void expirePassSessionCookies(CookieManager cm) {
        String expiredId = "pass_id=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
        String expiredEnabled = "pass_enabled=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
        for (String domain : PASS_DOMAINS) {
            cm.setCookie(domain, expiredId);
            cm.setCookie(domain, expiredEnabled);
            
            android.net.Uri uri = android.net.Uri.parse(domain);
            String host = uri.getHost();
            if (host != null) {
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                cm.setCookie(domain, expiredId + "; Domain=" + host);
                cm.setCookie(domain, expiredId + "; Domain=." + host);
                cm.setCookie(domain, expiredEnabled + "; Domain=" + host);
                cm.setCookie(domain, expiredEnabled + "; Domain=." + host);
            }
        }
    }
}
