/*
 * Clover - 4chan browser
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
package org.otacoo.chan.ui.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.ui.view.AuthWebView;
import org.otacoo.chan.utils.Logger;

/**
 * Visible Cloudflare check for an archive domain: shows the domain's challenge
 * page so the user can solve it manually (managed challenges normally pass on
 * their own). Closes itself as soon as a cf_clearance cookie appears.
 */
public class ArchiveUnlockController extends Controller {
    private static final String TAG = "ArchiveUnlockController";

    private final String domain;
    private final Runnable onUnlocked;
    private final Runnable onCancelled;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private boolean finished = false;

    public ArchiveUnlockController(Context context, String domain, Runnable onUnlocked, Runnable onCancelled) {
        super(context);
        this.domain = domain;
        this.onUnlocked = onUnlocked;
        this.onCancelled = onCancelled;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.setTitle(R.string.archive_unlock_title);
        navigation.swipeable = false;

        FrameLayout container = new FrameLayout(context);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        view = container;

        webView = new AuthWebView(context);
        webView.setWebViewClient(new WebViewClient());
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        webView.loadUrl("https://" + domain + "/");

        startPolling();
    }

    private void startPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished || !alive) return;
                String cookies = CookieManager.getInstance().getCookie("https://" + domain + "/");
                boolean cleared = cookies != null && cookies.contains("cf_clearance");
                Logger.d(TAG, "poll domain=" + domain + " cleared=" + cleared);
                if (cleared) {
                    finish(true);
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    private void finish(boolean unlocked) {
        if (finished) return;
        finished = true;

        if (unlocked) {
            onUnlocked.run();
        } else {
            onCancelled.run();
        }

        if (navigationController != null) {
            navigationController.popController();
        } else if (presentedByController != null) {
            stopPresenting();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (!finished) {
            finished = true;
            onCancelled.run();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
