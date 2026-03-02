/*
 * Clover - 4chan browser https://github.com/otacoo/Clover
 * Copyright (C) 2021 otacoo
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
package org.otacoo.chan.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.Logger;

/**
 * A standard WebView configured for 4chan sign-in/verification.
 * It does NOT intercept captcha requests, ensuring full-page features work natively.
 * It shares the same cookie and localStorage space as NewCaptchaLayout.
 */
public class AuthWebView extends WebView {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static boolean isOnWebViewThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static void runOnWebViewThread(Runnable runnable) {
        if (isOnWebViewThread()) {
            runnable.run();
        } else {
            Logger.w("AuthWebView", "WebView call posted from non-main thread");
            MAIN_HANDLER.post(runnable);
        }
    }

    public AuthWebView(Context context) {
        super(context);
        init();
    }

    public AuthWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            String databasePath = ctx.getDir("databases", 0).getPath();
            settings.setDatabasePath(databasePath);
        }
        
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(this, true);
        }

        String userAgent = ChanSettings.customUserAgent.get();
        if (userAgent.isEmpty()) {
            userAgent = WebSettings.getDefaultUserAgent(ctx);
        }
        settings.setUserAgentString(userAgent);

        // Use a standard client without any interception
        setWebViewClient(new WebViewClient());
    }

    @Override
    public void destroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush();
            } else {
                CookieSyncManager.createInstance(getContext());
                CookieSyncManager.getInstance().sync();
            }
        } catch (Exception ignored) {}
        super.destroy();
    }
}
