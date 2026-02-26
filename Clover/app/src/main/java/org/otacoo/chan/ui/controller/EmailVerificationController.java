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
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.widget.Toast;

import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.ui.helper.RefreshUIMessage;
import org.otacoo.chan.ui.view.AuthWebView;

import de.greenrobot.event.EventBus;

public class EmailVerificationController extends Controller {
    private AuthWebView webView;
    private String initialUrl;
    private String title = "Email Verification";
    private String[] requiredCookies;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable cookieWatcher;

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate() {
        super.onCreate();

        navigation.title = title;
        navigation.swipeable = false;

        webView = new AuthWebView(context);
        
        // 8chan.moe specific automation
        if (initialUrl.contains("8chan.moe") || initialUrl.contains("8chan.st")) {
            webView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                }
            });
        }

        // Load the verification page
        webView.loadUrl(initialUrl);

        view = webView;

        if (requiredCookies != null && requiredCookies.length > 0) {
            Toast.makeText(context, "Please solve the verification challenge to continue.", Toast.LENGTH_LONG).show();
            startCookieWatcher();
        }
    }

    private void startCookieWatcher() {
        cookieWatcher = new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                
                if (checkCookies()) {
                    Toast.makeText(context, "Verification successful!", Toast.LENGTH_SHORT).show();
                    EventBus.getDefault().post(new RefreshUIMessage("Verification successful"));
                    
                    if (navigationController != null) {
                        navigationController.popController();
                    } else if (presentedByController != null) {
                        stopPresenting();
                    }
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(cookieWatcher, 1000);
    }

    private boolean checkCookies() {
        String url = webView.getUrl();
        if (url == null) url = initialUrl;
        
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies == null) return false;

        boolean allFound = true;
        for (String required : requiredCookies) {
            boolean found = false;
            // Handle wildcard/prefix matching for cookies like TOS20250418
            if (cookies.contains(required)) {
                found = true;
            } else if (required.equals("TOS")) {
                // Look for any cookie starting with TOS (e.g., TOS20250418)
                if (cookies.matches(".*\\bTOS\\d+\\b.*") || cookies.contains("TOS=")) {
                    found = true;
                }
            }
            
            if (!found) {
                allFound = false;
                break;
            }
        }
        
        if (allFound) return true;
        
        // Try initial URL as well if current URL didn't match
        if (!url.equals(initialUrl)) {
            cookies = CookieManager.getInstance().getCookie(initialUrl);
            if (cookies != null) {
                for (String required : requiredCookies) {
                    boolean found = false;
                    if (cookies.contains(required)) {
                        found = true;
                    } else if (required.equals("TOS")) {
                        if (cookies.matches(".*\\bTOS\\d+\\b.*") || cookies.contains("TOS=")) {
                            found = true;
                        }
                    }
                    if (!found) return false;
                }
                return true;
            }
        }
        
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cookieWatcher != null) {
            handler.removeCallbacks(cookieWatcher);
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}
