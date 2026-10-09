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

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebSettings;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.site.SiteRequestModifier;
import org.otacoo.chan.core.site.sites.chan4.Chan4;
import org.otacoo.chan.ui.view.AuthWebView;
import org.otacoo.chan.utils.Logger;

/**
 * Opens 4chan's signin page with the same cookie setup the captcha uses: the
 * stored pass identity is pushed into the WebView and the page is loaded
 * natively, so it shows whether the stored 4chan_pass cookie is valid.
 * No cookies are cleared.
 */
public class Chan4SigninController extends Controller {
    private static final String TAG = "Chan4SigninController";
    private static final String SIGNIN_URL = "https://sys.4chan.org/signin";

    private final Chan4 site;
    private AuthWebView webView;

    public Chan4SigninController(Context context, Chan4 site) {
        super(context);
        this.site = site;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.title = context.getString(R.string.setup_site_4chan_signin);

        AuthWebView.runOnWebViewThread(this::setupWebView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (!alive) return;

        webView = new AuthWebView(context);
        // Always fetch the page fresh so a previously cached page cannot hide
        // the current signin state.
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);

        // Same cookie setup the captcha does: push the stored pass identity into
        // the WebView, then load the page natively with those cookies.
        SiteRequestModifier modifier = site.requestModifier();
        if (modifier != null) {
            modifier.modifyWebView(webView);
        }

        String cookies = site.getCookieStore().getCookieHeader(SIGNIN_URL);
        Logger.i(TAG, "Signin webview 4chan_pass length="
                + site.getCookieStore().getChanPass().length());
        if (cookies != null) {
            Logger.i(TAG, "Signin canonical cookies=[" + cookieNames(cookies) + "]");
        }

        webView.loadUrl(SIGNIN_URL);
        view = webView;
    }

    private static String cookieNames(String header) {
        StringBuilder names = new StringBuilder();
        for (String part : header.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                if (names.length() > 0) names.append(',');
                names.append(part, 0, eq);
            }
        }
        return names.toString();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
