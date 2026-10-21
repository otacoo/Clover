/*
 * Clover - 4chan browser - https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
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
package org.otacoo.chan.ui.captcha;

import android.webkit.CookieManager;

import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.sites.chan4.Chan4;

import okhttp3.Response;

/**
 * The cookies of the captcha session.
 *
 * The pass cookies (pass_id, pass_enabled, 4chan_pass) belong to the 4chan
 * account and must survive a session reset, only the cookies of the
 * fingerprinting of the connection (cf_clearance, _tcm, __cf_bm) are dropped
 * when the session is reset.
 */
final class CaptchaCookies {

    // The domains the captcha session lives on.
    static final String[] PASS_DOMAINS = {
            "https://4chan.org/", "https://www.4chan.org/", "https://boards.4chan.org/", "https://sys.4chan.org/",
            "https://4channel.org/", "https://www.4channel.org/", "https://boards.4channel.org/", "https://sys.4channel.org/"
    };

    // The cookies that belong to the fingerprinting of the connection.
    private static final String[] FINGERPRINT_COOKIES = {
            "cf_clearance", "_tcm", "__cf_bm"
    };

    private CaptchaCookies() {
    }

    /** True when the WebView has a valid cf_clearance cookie. */
    static boolean hasCloudflareClearance() {
        String c = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        return c != null && c.contains("cf_clearance");
    }

    /** True when the WebView has the cookies of the fingerprinting. */
    static boolean hasFingerprintCookies() {
        String c = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        return c != null && c.contains("_tcm");
    }

    /** Expires the fingerprint cookies, the pass cookies stay untouched. */
    static void expireFingerprintCookies() {
        CookieManager cm = CookieManager.getInstance();
        for (String domain : PASS_DOMAINS) {
            for (String name : FINGERPRINT_COOKIES) {
                String expired = name + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
                cm.setCookie(domain, expired);
                String host = android.net.Uri.parse(domain).getHost();
                if (host != null) {
                    if (host.startsWith("www.")) {
                        host = host.substring(4);
                    }
                    cm.setCookie(domain, expired + "; Domain=" + host);
                    cm.setCookie(domain, expired + "; Domain=." + host);
                }
            }
        }
        cm.flush();
    }

    /** Copies the cookies of a background response back into the WebView. */
    static void copyFromResponse(Site site, Response response) {
        CookieManager cm = CookieManager.getInstance();
        for (String header : response.headers("Set-Cookie")) {
            cm.setCookie("https://sys.4chan.org", header);
        }
        cm.flush();
        if (site instanceof Chan4) {
            ((Chan4) site).getCookieStore().onServerCookies(response.headers("set-cookie"));
        }
    }
}
