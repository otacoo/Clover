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
import android.view.MotionEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteRequestModifier;
import org.otacoo.chan.ui.helper.PostHelper;
import org.otacoo.chan.ui.view.AuthWebView;

import okhttp3.HttpUrl;

public class ReportController extends Controller {
    private Post post;
    private SiteRequestModifier siteRequestModifier;

    public ReportController(Context context, Post post) {
        super(context);
        this.post = post;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate() {
        super.onCreate();
        navigation.title = context.getString(R.string.report_screen, PostHelper.getTitle(post, null));

        AuthWebView.runOnWebViewThread(this::setupWebView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (!alive) return;

        Site site = post.board.getSite();
        HttpUrl url = site.endpoints().report(post);

        WebView webView = new WebView(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && event.getY() < getMeasuredHeight() * 0.75f) {
                    requestDisallowInterceptTouchEvent(true);
                }
                return super.onTouchEvent(event);
            }
        };

        siteRequestModifier = site.requestModifier();
        if (siteRequestModifier != null) {
            siteRequestModifier.modifyWebView(webView);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.loadUrl(url.toString());
        view = webView;
    }
}
