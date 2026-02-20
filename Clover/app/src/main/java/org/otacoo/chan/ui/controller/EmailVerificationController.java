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
import android.content.Context;
import android.webkit.WebView;

import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.ui.view.AuthWebView;

public class EmailVerificationController extends Controller {
    private AuthWebView webView;
    private String initialUrl;
    private String title = "Email Verification";

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate() {
        super.onCreate();

        navigation.title = title;
        navigation.swipeable = false;

        webView = new AuthWebView(context);

        // Load the verification page
        webView.loadUrl(initialUrl);

        view = webView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
