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

import androidx.annotation.Nullable;

import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteRequestModifier;
import org.otacoo.chan.ui.view.AuthWebView;

public class WebViewController extends Controller {
    private AuthWebView webView;
    private final String url;
    private final String title;
    @Nullable
    private final Site site;

    public WebViewController(Context context, String url, String title) {
        this(context, url, title, null);
    }

    public WebViewController(Context context, String url, String title, @Nullable Site site) {
        super(context);
        this.url = url;
        this.title = title;
        this.site = site;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.title = title;

        AuthWebView.runOnWebViewThread(this::setupWebView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (!alive) return;

        webView = new AuthWebView(context);

        // Push the site's saved cookies into the WebView before loading, so
        // pages that identify you (e.g. 4chan /banned) see the same identity
        // the app uses for posting.
        if (site != null) {
            SiteRequestModifier modifier = site.requestModifier();
            if (modifier != null) {
                modifier.modifyWebView(webView);
            }
        }

        webView.loadUrl(url);

        view = webView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    public boolean onBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onBack();
    }
}
