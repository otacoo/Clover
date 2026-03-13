/*
 * Clover - 4chan browser https://github.com/otacoo/Clover
 * Copyright (C) 2025 nuudev https://github.com/nuudev/BlueClover
 * Copyright (C) 2026 otacoo
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

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;
import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * WebView-based layout for handling the new 4chan captcha and Cloudflare Turnstile.
 */
public class NewCaptchaLayout extends WebView implements AuthenticationLayoutInterface {
    private static final String TAG = "NewCaptchaLayout";

    @Inject
    OkHttpClient okHttpClient;

    /** True when we are showing a captcha UI (asset challenges/legacy image) that would be lost if the user backs out. */
    private volatile boolean showingActiveCaptcha;
    /** True when a post cooldown is active (4chan only). Prevents accidental dismissal. */
    private volatile boolean cooldownActive;
    /** True when we have already reported a solved captcha to the callback for the current session. */
    private boolean reportedCompletion;

    private static final int NATIVE_PAYLOAD_MAX_RETRIES = 5;
    private static final int NATIVE_PAYLOAD_RETRY_DELAY_MS = 500;

    private static volatile String ticket = "";

    /** The key used for global 4chan cooldown tracking across boards and threads. */
    private static final String GLOBAL_4CHAN_KEY = "global_4chan_cooldown";
    /** The key used for global tracking of the last "Get Captcha" request time. */
    private static final String LAST_REQUEST_KEY = "last_captcha_request_time";

    private static final Set<NewCaptchaLayout> visibleInstances = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<String, Long> globalCooldowns = new ConcurrentHashMap<>();
    private static final Map<String, String> globalPayloads = new ConcurrentHashMap<>();
    private static final Map<String, Long> globalExpiries = new ConcurrentHashMap<>();

    private AuthenticationLayoutCallback callback;
    private String baseUrl;
    private String board;
    private int thread_id;
    private org.otacoo.chan.core.site.Site site;
    private String cachedUserAgent;

    /** True when an error overlay is showing, preventing any automated resets from clearing it. */
    private volatile boolean showingOverlay;

    /** Retry state for extracting payload from the native captcha page without clobbering its DOM (needed for JS challenges). */
    private int nativePayloadRetryAttempts = 0;

    /** Internal flag to distinguish between the WebView loading a web page vs. our local assets for UI elements. */
    private volatile boolean lastResponseWasAsset;
    /** Internal flag to skip OkHttp interception for the next load (e.g., when reloading with custom headers). */
    private volatile boolean skipInterceptNextLoad;

    /** Remembers the last payload applied to the UI to prevent redundant redraws or infinite loops. */
    private String lastAppliedPayload;

    @SuppressWarnings("this-escape")
    public NewCaptchaLayout(Context context) {
        super(context);
        commonInit();
    }

    @SuppressWarnings("this-escape")
    public NewCaptchaLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        commonInit();
    }

    @SuppressWarnings("this-escape")
    public NewCaptchaLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        commonInit();
    }

    // Configures basic WebView settings and JS interfaces
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    private void commonInit() {
        inject(this);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // Disable system Force Dark to avoid interference with our manual dark mode implementation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        // Ensure cookies are accepted and shared across sessions
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this, true);

        WebView.setWebContentsDebuggingEnabled(true);

        // Set a default user agent from settings
        String userAgent = ChanSettings.customUserAgent.get();
        if (userAgent.isEmpty()) {
            userAgent = WebSettings.getDefaultUserAgent(getContext());
        }
        settings.setUserAgentString(userAgent);
        cachedUserAgent = userAgent;

        setWebViewClient(createCaptchaWebViewClient());
        setBackgroundColor(getAttrColor(getContext(), R.attr.backcolor));
        addJavascriptInterface(new CaptchaInterface(), "CaptchaCallback");
    }

    private void onCaptchaResponse(String response) {
        if (response == null || response.trim().isEmpty()) return;
        Logger.i(TAG, "onCaptchaResponse: " + response);
        // always show responses from the page regardless of preference
        maybeToast(response, false);
        // #t-resp means we should not proceed to submission until the user has seen the message.
    }

    // Connects the layout to a board/thread and the completion callback
    @Override
    public void initialize(Loadable loadable, AuthenticationLayoutCallback callback) {
        this.callback = callback;
        this.reportedCompletion = false;
        this.site = loadable.site;
        this.board = loadable.boardCode;
        this.thread_id = loadable.no;

        SiteAuthentication auth = loadable.site.actions().postAuthenticate();
        this.baseUrl = auth.baseUrl;
        loadable.site.requestModifier().modifyWebView(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // Identifies the global cooldown bucket for the current site or board
    private String getGlobalKey() {
        boolean is4chan = (baseUrl != null && baseUrl.contains("4chan.org")) || (site != null && "4chan".equalsIgnoreCase(site.name()));
        return is4chan ? GLOBAL_4CHAN_KEY : (board + "_" + thread_id);
    }

    // Returns how many seconds are left on the 4chan post cooldown
    public int getCooldownRemainingSeconds() {
        String key = getGlobalKey();
        Long endTime = globalCooldowns.get(key);
        if (endTime == null) return 0;
        return (int) Math.max(0, (endTime - System.currentTimeMillis()) / 1000);
    }

    // Returns how many seconds are left before a new captcha can be requested
    public int getRequestCooldownRemainingSeconds() {
        Long lastRequest = globalCooldowns.get(LAST_REQUEST_KEY);
        if (lastRequest == null) return 0;
        int remaining = (int) ((lastRequest + 30000L - System.currentTimeMillis()) / 1000);
        return Math.max(0, remaining);
    }

    // Checks if we are currently waiting for any cooldown to expire
    public boolean onCooldownNow() {
        return getCooldownRemainingSeconds() > 0;
    }

    // Tells the caller if we are currently displaying a cooldown timer
    public boolean isShowingCooldownUI() {
        return cooldownActive;
    }

    // Restores the last known UI state or reloads the captcha page
    @Override
    public void reset() {
        reportedCompletion = false;
        
        // Preserve active challenge or error overlay if user returns to it.
        if (showingActiveCaptcha || showingOverlay) {
            maybeToast(showingOverlay ? "Returning to error details." : "Returning to active captcha session.", false);
            onCaptchaLoaded();
            return;
        }

        String key = getGlobalKey();
        int displayRemaining = Math.max(getCooldownRemainingSeconds(), getRequestCooldownRemainingSeconds());

        if (displayRemaining > 0) {
            cooldownActive = true;
            showingActiveCaptcha = true;
            String savedPayload = globalPayloads.get(key);
            
            try {
                JSONObject obj = (savedPayload != null && !savedPayload.equals("null")) ? new JSONObject(savedPayload) : new JSONObject();
                JSONObject inner = obj.optJSONObject("twister");
                Objects.requireNonNullElse(inner, obj).put("pcd", displayRemaining);
                savedPayload = obj.toString();
            } catch (Exception ignored) {}

            String assetHtml = loadAssetWithCaptchaData(savedPayload != null ? savedPayload : "{\"pcd\":" + displayRemaining + "}");
            if (assetHtml != null) {
                lastResponseWasAsset = true;
                loadDataWithBaseURL("https://sys.4chan.org/", assetHtml, "text/html", "UTF-8", "https://sys.4chan.org/");
                onCaptchaLoaded();
                return;
            }
        }
        
        cooldownActive = false;
        globalPayloads.remove(key);
        globalCooldowns.remove(key);
        hardReset(false, false);
    }

    @Override
    public void hardReset() {
        hardReset(true, false);
    }

    /**
     * Shows a standalone error page after a failed post (e.g. IP ban, abuse block).
     */
    public void showAuthenticationError(String errorMessage) {
        showUnifiedOverlay(errorMessage, true);
    }

    private String buildOverlayJson(String message, boolean isError) {
        try {
            JSONObject overlay = new JSONObject();
            overlay.put("message", message);
            overlay.put("isError", isError);
            overlay.put("showGetCaptcha", true);
            overlay.put("showReset", true);
            JSONObject root = new JSONObject();
            root.put("overlay", overlay);
            return root.toString();
        } catch (Exception e) {
            return "{\"overlay\":{\"message\":\"An error occurred.\",\"isError\":true}}";
        }
    }

    private void showUnifiedOverlay(String message, boolean isError) {
        showingOverlay = true;
        showingActiveCaptcha = false;
        if (isError) post(() -> maybeToast(message, false));
        String html = loadAssetWithCaptchaData(buildOverlayJson(message, isError));
        if (html != null) {
            loadDataWithBaseURL("https://clover.local/", html, "text/html", "UTF-8", null);
        }
    }

    // Performs a full reload of the 4chan captcha endpoint
    private void hardReset(boolean includeCacheBuster, boolean includeTicket) {
        AndroidUtils.runOnUiThread(() -> {
            // If an error overlay was shown (e.g. IP ban after post), don't navigate away.
            if (showingOverlay) return;
            showingActiveCaptcha = false;
            showingOverlay = false;
            reportedCompletion = false;
            nativePayloadRetryAttempts = 0;
            lastAppliedPayload = null;
            
            if (includeCacheBuster) {
                // Force bypass of the local disk/memory cache for the main request to refresh _tcm/_tcs
                getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            } else {
                getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            }

            String ticketParam = (includeTicket && !ticket.isEmpty()) ? "&ticket=" + urlEncode(ticket) : "";
            String url = "https://sys.4chan.org/captcha?board=" + board + (thread_id > 0 ? "&thread_id=" + thread_id : "") + ticketParam;
            
            if (includeCacheBuster) {
                url += (url.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
            }
            
            lastResponseWasAsset = false;
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://boards.4chan.org/" + board + "/thread/" + thread_id);
            
            loadUrl(url, headers);
        });
    }

    // Handles request interception and page lifecycle events
    private WebViewClient createCaptchaWebViewClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("spur.us") || url.contains("mcl.io")) return null;

                boolean isMainFrame = request.isForMainFrame();

                // Only intercept main-frame navigations; subresources should go through so
                // postMessage payloads reach our JS hook. When an active captcha page
                // is running we also avoid intercepting its own refresh navigations.
                boolean isCaptchaRequest = (board != null && !board.isEmpty()) 
                        ? url.startsWith("https://sys.4chan.org/captcha?board=" + board)
                        : url.contains("sys.4chan.org/captcha?");

                if (isMainFrame && isCaptchaRequest && showingActiveCaptcha) {
                    if (lastResponseWasAsset) {
                        return null;
                    }

                    // Reset per-page state so that the incoming page is evaluated fresh.
                    showingActiveCaptcha = false;
                    lastResponseWasAsset = false;
                    // Keep skipInterceptNextLoad=true so this re-navigation (triggered by mcl.js /
                    // fingerprinting completing) also loads natively. This gives the JS time to work.
                    skipInterceptNextLoad = true;
                    return null;
                }

                if (isCaptchaRequest && isMainFrame && !skipInterceptNextLoad) {
                    if (hasCloudflareClearance() && hasFingerprintCookies()) {
                        return interceptCaptchaRequest(request);
                    } else {
                        // missing clearance or fingerprint – fall back to native page so
                        // mcl.js / cloudflare can run and issue required cookies.
                        Logger.i(TAG, "interceptCaptchaRequest: Missing required cookies, skipping intercept");
                        return null;
                    }
                }
                
                if (isMainFrame && isCaptchaRequest) {
                    skipInterceptNextLoad = false;
                    lastResponseWasAsset = false;
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url == null || !url.contains("sys.4chan.org")) return;

                // DO NOT interfere with sign-in or verification pages
                if (url.contains("/signin") || url.contains("action=verify")) {
                    return;
                }

                if (url.endsWith("/post")) {
                    handlePostResult();
                    return;
                }

                // Asset page is self-contained; theming/hooks are only for the native page
                if (lastResponseWasAsset) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (getWindowToken() != null) onCaptchaLoaded();
                    }, 500);
                    return;
                }

                injectThemingAndHooks();

                if (!url.contains("/captcha")) {
                    handleNonCaptchaRedirect(url);
                    return;
                }

                extractPayloadFromNativePageAndLoadAsset(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                if (host != null && (host.contains("4chan.org") || host.contains("cloudflare"))) {
                    return false;
                }
                AndroidUtils.openLink(url);
                return true;
            }
        };
    }

    // Injects dark mode styles and JS message listeners into the page
    private void injectThemingAndHooks() {
        boolean isDark = !ThemeHelper.theme().isLightTheme;
        boolean singleView = false;
        if (site != null && site.name().equalsIgnoreCase("4chan")) {
            // SitesSetupController uses the site to find settings
            org.otacoo.chan.core.site.sites.chan4.Chan4 chan4 = (org.otacoo.chan.core.site.sites.chan4.Chan4) site;
            singleView = chan4.getSingleViewCaptchas().get();
        }
        
        final boolean finalSingleView = singleView;

        String js = "(function() {" +
                "  var isDark = " + isDark + ";" +
                "  var isSingleView = " + finalSingleView + ";" +
                "  var meta = document.createElement('meta');" +
                "  meta.name = 'color-scheme';" +
                "  meta.content = isDark ? 'dark' : 'light';" +
                "  document.head.appendChild(meta);" +
                "  document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';" +
                "  var cf = document.querySelector('.cf-turnstile');" +
                "  if (cf && !cf.hasAttribute('data-theme')) cf.setAttribute('data-theme', isDark ? 'dark' : 'light');" +
                "  if (window.parent && !window.__postMessageHookInstalled) {" +
                "    window.__postMessageHookInstalled = true;" +
                "    var original = window.parent.postMessage.bind(window.parent);" +
                "    window.parent.postMessage = function(msg, origin) {" +
                "      if (msg && msg.twister) {" +
                "        msg.twister.single_view = isSingleView;" +
                "        try { CaptchaCallback.onCaptchaPayloadReady(encodeURIComponent(JSON.stringify(msg))); } catch(e) {}" +
                "        if (msg.twister.challenge && msg.twister.response) {" +
                "          try { CaptchaCallback.onCaptchaEntered(msg.twister.challenge, msg.twister.response); } catch(e) {}" +
                "        }" +
                "      }" +
                "      return original(msg, origin);" +
                "    };" +
                "  }" +
                "  (function pollResp() {" +
                "    var el = document.getElementById('t-resp');" +
                "    if (el && el.value) {" +
                "      var val = el.value.trim();" +
                "      if (val && !window.__lastTResp) {" +
                "        window.__lastTResp = val;" +
                "        try { CaptchaCallback.onCaptchaResponse(val); } catch(e) {}" +
                "      } else if (val && val !== window.__lastTResp) {" +
                "        window.__lastTResp = val;" +
                "        try { CaptchaCallback.onCaptchaResponse(val); } catch(e) {}" +
                "      }" +
                "      var chall = document.getElementById('t-challenge') || {value:''};" +
                "      CaptchaCallback.onCaptchaEntered(chall.value, el.value);" +
                "    } else if (el) setTimeout(pollResp, 800);" +
                "  })();" +
                "})();";
        evaluateJavascript(js, null);
    }

    // Uses OkHttp to fetch captcha data in the background to avoid blank pages
    private WebResourceResponse interceptCaptchaRequest(WebResourceRequest webRequest) {
        String url = webRequest.getUrl().toString();
        try {
            Request.Builder builder = new Request.Builder().url(url);
            
            //Copy all headers exactly as the WebView intended them to ensure consistent fingerprints (Client Hints, Accept-Language, etc.)
            Map<String, String> headers = webRequest.getRequestHeaders();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            
            //Override cookies with Clover's aggregated version (ensures cross-subdomain consistency)
            String aggregatedCookies = get4chanCookieHeader();
            if (aggregatedCookies != null) {
                builder.header("Cookie", aggregatedCookies);
            }
            
            // Ensure Referer is set if missing
            if (!headers.containsKey("Referer")) {
                builder.header("Referer", "https://boards.4chan.org/" + board + "/thread/" + thread_id);
            }

            Request request = builder.build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                copyCookies(response);

                // if we see the fingerprint landing page, tokens are likely stale.
                if (body.contains("mcl.io") || body.contains("spur.us")) {
                    Logger.i(TAG, "interceptCaptchaRequest: fingerprint page detected, deferring to native load");
                    maybeToast("Fingerprinting appears stale. Refreshing...", false);
                    refreshFingerprintSession();
                    skipInterceptNextLoad = true;
                    return null;
                }

                String payload = extractTwisterPayload(body);
                if (payload != null) {
                    persistTicket(payload);
                    String assetHtml = loadAssetWithCaptchaData(payload);
                    if (assetHtml != null) {
                        lastResponseWasAsset = true;
                        showingActiveCaptcha = true;
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(assetHtml.getBytes(StandardCharsets.UTF_8)));
                    }
                } else {
                    Logger.i(TAG, "interceptCaptchaRequest: No payload found in response body");
                    handleSiteErrorInIntercept(body);
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "Intercept failed", e);
        }
        return null;
    }

    // Parses a JSON payload and updates the UI with cooldown or challenge data
    private void applyPayload(String payload, String sourceUrl) {
        if (TextUtils.isEmpty(payload) || "null".equals(payload)) return;
        
        // Avoid redundant UI reloads if the payload hasn't changed.
        if (payload.equals(lastAppliedPayload)) {
            return;
        }
        lastAppliedPayload = payload;

        persistTicket(payload);
        globalPayloads.put(getGlobalKey(), payload);

        try {
            JSONObject root = new JSONObject(payload);
            JSONObject obj = root.optJSONObject("twister");
            if (obj == null) obj = root;

            int pcd = obj.optInt("pcd", -1);
            int cd = obj.optInt("cd", -1);
            int ttl = obj.optInt("ttl", obj.optInt("expiry", -1));
            
            if (ttl > 0) {
                globalExpiries.put(getGlobalKey(), System.currentTimeMillis() + (ttl * 1000L));
                scheduleExpiryNotice(getGlobalKey(), ttl);
            }

            if (pcd > 0 || cd > 0) {
                startCooldownTracking(Math.max(pcd, cd));
                cooldownActive = true;
            }

            if (obj.has("img") || obj.has("tasks") || pcd > 0 || cd > 0) {
                String assetHtml = loadAssetWithCaptchaData(payload);
                if (assetHtml != null) {
                    lastResponseWasAsset = true;
                    showingActiveCaptcha = true;
                    loadDataWithBaseURL(sourceUrl, assetHtml, "text/html", "UTF-8", sourceUrl);
                    onCaptchaLoaded();
                    return;
                }
            }

            String err = obj.optString("error", "");
            if (!err.isEmpty() && pcd <= 0 && cd <= 0) {
                showUnifiedOverlay(err, true);
                return;
            }

            // captcha may be skipped for good-standing / verified accounts
            String lower = payload.toLowerCase();
            if (lower.contains("verified") || lower.contains("not required")) {
                if (onCooldownNow()) {
                    globalCooldowns.remove(getGlobalKey());
                    cooldownActive = false;
                    maybeToast("Cooldown finished. You can now request a captcha.", false);
                }
                onCaptchaEntered("", "");
                return;
            }

            showUnifiedOverlay("Tap to request a captcha again.", false);
        } catch (Exception e) {
            Logger.e(TAG, "Failed to post: applyPayload failed", e);
        }
    }

    // Schedules a toast notification for when the current captcha session expires
    private void scheduleExpiryNotice(final String key, int seconds) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Long expiryTime = globalExpiries.get(key);
            if (expiryTime != null && expiryTime <= System.currentTimeMillis() + 1000) {
                globalExpiries.remove(key);
                globalPayloads.remove(key);
                if (visibleInstances.contains(this)) {
                    maybeToast("Captcha session expired.", false);
                }
            }
        }, seconds * 1000L);
    }

    // Checks for submission errors on the post-reply page
    private void handlePostResult() {
        evaluateJavascript("(function(){" +
                "  var el = document.getElementById('errmsg');" +
                "  var body = document.body ? document.body.innerText : '';" +
                "  return JSON.stringify({ " +
                "    msg: el ? el.innerText : ''," +
                "    full: body" +
                "  });" +
                "})()", result -> {
            try {
                JSONObject res = new JSONObject(result != null ? result : "{}");
                String msg = res.optString("msg").trim();
                String full = res.optString("full").trim();

                AndroidUtils.runOnUiThread(() -> {
                    if (!msg.isEmpty() && !"null".equals(msg)) {
                        showUnifiedOverlay(msg, true);
                    } else if (full.contains("Post successful")) {
                        // Success - no reload needed here, callback handles it
                        Logger.i(TAG, "Post successful detected on /post page");
                    } else if (full.contains("Error") || full.contains("Verification") || full.contains("mistyped")) {
                        // Generic error detection fallback
                        String display = "Post failed or verification error.";
                        if (full.contains("Error:")) {
                            int start = full.indexOf("Error");
                            int end = Math.min(start + 120, full.length());
                            display = full.substring(start, end).replace("\n", " ") + "...";
                        }
                        showUnifiedOverlay(display, true);
                    } else {
                        // Unrecognized state – reload captcha
                        hardReset(false, false);
                    }
                });
            } catch (Exception e) {
                Logger.e(TAG, "handlePostResult parse failed", e);
                hardReset(false, false);
            }
        });
    }

    // Handles unexpected redirects to the 4chan home page
    private void handleNonCaptchaRedirect(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if ((path == null || "/".equals(path) || path.isEmpty()) && !lastResponseWasAsset) {
            hardReset();
        } else if (lastResponseWasAsset) {
            onCaptchaLoaded();
        }
    }

    // Scans the native page's JS environment for captcha data
    private void extractPayloadFromNativePageAndLoadAsset(String url) {

        String js = "(function(){" +
                "  var res = { html: document.documentElement.outerHTML };" +
                "  try { res.payload = window.pcd_payload || (window.t_pcd ? {pcd:window.t_pcd} : null); } catch(e){}" +
                "  var err = document.getElementById('errmsg');" +
                "  if (err) res.errMsg = err.innerText;" +
                "  return res;" +
                "})()";

        evaluateJavascript(js, raw -> {
            try {
                JSONObject res = new JSONObject(raw);
                String html = res.optString("html");
                Object p = res.opt("payload");
                String payload = (p != null && !p.toString().equals("null")) ? p.toString() : extractTwisterPayload(html);
                String errMsg = res.optString("errMsg");

                Logger.i(TAG, "extractPayload: errMsg=" + errMsg + ", payloadFound=" + (payload != null));

                if (!errMsg.isEmpty()) {
                    showUnifiedOverlay(errMsg, true);
                    return;
                }

                if (payload != null && !payload.equals("null")) {
                    applyPayload(payload, url);
                } else {
                    handleNoPayloadInNativePage(html, url);
                }
            } catch (Exception e) {
                Logger.e(TAG, "Extraction failed", e);
            }
        });
    }

    // Retries extraction if no payload is found but a challenge element exists
    private void handleNoPayloadInNativePage(String html, String url) {
        boolean isChallenge = html.contains("cf-turnstile") || html.contains("challenges.cloudflare.com") || html.contains("spur.us") || html.contains("t-root");
        if (isChallenge) {
            showingActiveCaptcha = true;
            onCaptchaLoaded();
        } else if (nativePayloadRetryAttempts < NATIVE_PAYLOAD_MAX_RETRIES) {
            nativePayloadRetryAttempts++;
            new Handler(Looper.getMainLooper()).postDelayed(() -> extractPayloadFromNativePageAndLoadAsset(url), NATIVE_PAYLOAD_RETRY_DELAY_MS);
        } else {
            showUnifiedOverlay("Captcha failed to load.", true);
        }
    }

    // Searches raw HTML for the "twister" JSON object sent via postMessage
    private String extractTwisterPayload(String html) {
        if (html == null || html.contains("cf-turnstile")) return null;
        int idx = html.indexOf("postMessage");
        while (idx != -1) {
            int start = html.indexOf("{", idx);
            if (start != -1) {
                int depth = 0, i = start;
                while (i < html.length()) {
                    char c = html.charAt(i++);
                    if (c == '{') depth++; else if (c == '}') depth--;
                    if (depth == 0) {
                        String candidate = html.substring(start, i);
                        if (candidate.contains("pcd") || candidate.contains("tasks") || candidate.contains("img") || candidate.contains("ticket") || candidate.contains("cd") || candidate.contains("challenge")) {
                            return candidate;
                        }
                        break;
                    }
                }
            }
            idx = html.indexOf("postMessage", idx + 1);
        }
        return null;
    }

    // Replaces placeholders in the local HTML template with current captcha data
    private String loadAssetWithCaptchaData(String json) {
        try (InputStream is = getContext().getAssets().open("captcha/new_captcha.html");
             Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
            String html = scanner.useDelimiter("\\A").next();
            boolean isDark = !ThemeHelper.theme().isLightTheme;
            
            boolean singleView = false;
            if (site != null && site.name().equalsIgnoreCase("4chan")) {
                org.otacoo.chan.core.site.sites.chan4.Chan4 chan4 = (org.otacoo.chan.core.site.sites.chan4.Chan4) site;
                singleView = chan4.getSingleViewCaptchas().get();
            }
            
            // Add single_view flag to JSON if not present
            if (singleView) {
                try {
                    JSONObject obj = new JSONObject(json);
                    JSONObject inner = obj.optJSONObject("twister");
                    if (inner != null) {
                        inner.put("single_view", true);
                        json = obj.toString();
                    } else if (obj.has("tasks")) {
                        obj.put("single_view", true);
                        json = obj.toString();
                    }
                } catch (Exception ignored) {}
            }

            // derive colours from theme attributes
            ThemeHelper.ColorPair tc = ThemeHelper.getThemeBackgroundForeground(getContext());
            int accentCol = ThemeHelper.theme().accentColor.color;
            String accentHex = String.format("#%06X", (0xFFFFFF & accentCol));
            String accentFg = getContrastColor(accentCol);

            return html.replace("__CLOVER_JSON__", json.replace("</script>", "<\\/script>"))
                    .replace("__COLOR_SCHEME__", isDark ? "dark" : "light")
                    .replace("__C_BG__", tc.bgHex)
                    .replace("__C_FG__", tc.fgHex)
                    .replace("__C_ACCENT__", accentHex)
                    .replace("__C_TIMER_SHADOW__", isDark ? "0 0 1px rgba(255,255,255,0.25)" : "0 1px 2px rgba(0,0,0,0.15)")
                    .replace("__C_LINK__", isDark ? "#b8b8b8" : "#1565c0")
                    .replace("__C_INPUT_BG__", isDark ? "#1e1e1e" : "#fff")
                    .replace("__C_INPUT_FG__", isDark ? "#e0e0e0" : "#000")
                    .replace("__C_INPUT_BORDER__", isDark ? "#444" : "#ccc")
                    .replace("__C_BTN_BG__", accentHex)
                    .replace("__C_BTN_FG__", accentFg)
                    .replace("__C_BTN_BORDER__", isDark ? "rgba(255,255,255,0.15)" : "rgba(0,0,0,0.15)")
                    .replace("__C_MUTED__", isDark ? "#b0b0b0" : "#555")
                    .replace("__C_BG_OVERLAY__", tc.bgHex)
                    .replace("__C_LINK_CLICKABLE__", accentHex);
        } catch (Exception e) {
            Logger.e(TAG, "Load asset failed", e);
            return null;
        }
    }

    private String getContrastColor(int color) {
        double luminance = (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF)) / 255;
        return luminance > 0.5 ? "#000000" : "#ffffff";
    }

    // Saves the "4chan-tc-ticket" to static state and localStorage
    private void persistTicket(String payload) {
        if (payload == null || payload.equals("null")) return;
        try {
            JSONObject obj = new JSONObject(payload);
            JSONObject inner = obj.optJSONObject("twister");
            String t = (inner != null ? inner : obj).optString("ticket", "");
            if (!t.isEmpty()) {
                ticket = t;
                AndroidUtils.runOnUiThread(() -> evaluateJavascript("localStorage.setItem('4chan-tc-ticket','" + t.replace("'", "\\'") + "')", null));
            }
        } catch (Exception ignored) {}
    }

    // Begins background tracking of a new post cooldown timer
    private void startCooldownTracking(int seconds) {
        String key = getGlobalKey();
        globalCooldowns.put(key, System.currentTimeMillis() + (seconds * 1000L));
        maybeToast("Cooldown to request new captcha started: (" + seconds + "s)", false);
    }

    // Returns true if the WebView already has a valid cf_clearance cookie
    private boolean hasCloudflareClearance() {
        String c = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        return c != null && c.contains("cf_clearance") && c.contains("__cf_bm");
    }

    private boolean hasFingerprintCookies() {
        String c = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        return c != null && c.contains("_tcm");
    }

    // Aggregates cookies from all 4chan subdomains for background requests
    private String get4chanCookieHeader() {
        CookieManager cm = CookieManager.getInstance();
        Set<String> set = new LinkedHashSet<>();
        for (String b : new String[]{"https://sys.4chan.org", "https://boards.4chan.org", "https://sys.4channel.org", "https://boards.4channel.org"}) {
            String c = cm.getCookie(b);
            if (c != null) {
                for (String part : c.split(";\\s*")) {
                    if (!part.isEmpty()) set.add(part);
                }
            }
        }
        return set.isEmpty() ? null : TextUtils.join("; ", set);
    }

    // Clears only in-memory state and localStorage so 4chan can re-run fingerprinting (mcl.js / Cloudflare). Does NOT delete any cookies.
    private void refreshFingerprintSession() {
        ticket = "";
        globalPayloads.clear();
        globalCooldowns.clear();
        globalExpiries.clear();
        AndroidUtils.runOnUiThread(() -> {
            showingOverlay = false;
            evaluateJavascript("localStorage.clear(); sessionStorage.clear();", null);
        });
    }

    private void clearFingerprintCookies() {
        // Clear stale ticket immediately
        ticket = "";

        // Drop all in-memory captcha state so nothing stale bleeds into the fresh session.
        globalPayloads.clear();
        globalCooldowns.clear();
        globalExpiries.clear();

        AndroidUtils.runOnUiThread(() -> {
            showingOverlay = false;  // allow hardReset to proceed past the guard
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();

            evaluateJavascript("localStorage.clear(); sessionStorage.clear();", null);

            maybeToast("4chan session cleared.", true);

            // Do NOT re-include the (now erased) ticket in the next load.
            hardReset(true, false);
        });
    }

    // Syncs cookies from a background OkHttp response back to the WebView
    private void copyCookies(Response r) {
        CookieManager cm = CookieManager.getInstance();
        for (String header : r.headers("Set-Cookie")) {
            cm.setCookie("https://sys.4chan.org", header);
        }
        cm.flush();
    }

    // Convenience wrappers for showing a long toast on the UI thread
    private void showLongToast(final String msg) {
        AndroidUtils.runOnUiThread(() -> Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show());
    }

    // Show a toast if user has enabled captcha toasts or when it is forced.
    private void maybeToast(final String msg, boolean force) {
        if (force || AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false)) {
            showLongToast(msg);
        }
    }

    private String urlEncode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    // Handles site-wide errors (like bans) detected during interception
    private void handleSiteErrorInIntercept(String body) {
        String err = body.contains("{") ? extractRawJsonError(body) : null;
        if (err == null && body.contains("<")) err = stripHtml(body);
        if (!TextUtils.isEmpty(err) && err.length() < 500) {
            final String finalErr = err;
            AndroidUtils.runOnUiThread(() -> showUnifiedOverlay(finalErr, true));
        }
    }

    private static String extractRawJsonError(String b) {
        try { return new JSONObject(b).optString("error", ""); } catch (Exception e) { return ""; }
    }

    static String stripHtml(String s) {
        return (s == null) ? "" : s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    // Focuses the view and hides the keyboard when captcha is ready
    private void onCaptchaLoaded() {
        requestFocus();
        AndroidUtils.hideKeyboard(this);
    }

    // Finalizes the solve and notifies the reply layout
    private void onCaptchaEntered(String challenge, String response) {
        globalCooldowns.remove(getGlobalKey());
        cooldownActive = false;
        if (reportedCompletion) return;
        reportedCompletion = true;
        showingActiveCaptcha = false;
        if (callback != null) {
            callback.onAuthenticationComplete(this, challenge, response);
        }
    }

    @Override
    public void reload() {
        Logger.i(TAG, "reload: performing hardReset()");
        hardReset();
    }

    @Override public boolean requireResetAfterComplete() { return false; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        visibleInstances.add(this);
        if (getWindowToken() != null) AndroidUtils.hideKeyboard(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        visibleInstances.remove(this);
    }

    @Override 
    public void onDestroy() {
        reportedCompletion = true;
        callback = null;
        showingActiveCaptcha = false;
        visibleInstances.remove(this);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo o) { return null; }

    // Prevents parent scroll interference when interacting with the captcha
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float h = getMeasuredWidth() * 0.375f;
            if (event.getY() < h) {
                ViewParent p = this;
                while (p != null) {
                    p.requestDisallowInterceptTouchEvent(true);
                    p = p.getParent();
                }
            }
        }
        
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // JavaScript Bridge implementation
    public class CaptchaInterface {
        @JavascriptInterface
        public void onCaptchaLoaded() {
            AndroidUtils.runOnUiThread(NewCaptchaLayout.this::onCaptchaLoaded);
        }

        @JavascriptInterface
        public void onCaptchaEntered(String c, String r) {
            AndroidUtils.runOnUiThread(() -> NewCaptchaLayout.this.onCaptchaEntered(c, r));
        }

        @JavascriptInterface
        public void onRequestCaptcha() {
            AndroidUtils.runOnUiThread(() -> {
                showingOverlay = false;  // allow hardReset to proceed past the guard
                NewCaptchaLayout.this.hardReset(false, true);
            });
        }

        @JavascriptInterface
        public void onCaptchaResponse(String r) {
            AndroidUtils.runOnUiThread(() -> NewCaptchaLayout.this.onCaptchaResponse(r));
        }

        @JavascriptInterface
        public void onCaptchaPayloadReady(String p) {
            try {
                String decoded = URLDecoder.decode(p, StandardCharsets.UTF_8.name());
                AndroidUtils.runOnUiThread(() -> applyPayload(decoded, getUrl()));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void saveTicket(String t) {
            ticket = t;
        }

        @JavascriptInterface
        public void onResetSession() {
            AndroidUtils.runOnUiThread(NewCaptchaLayout.this::onResetSession);
        }
    }

    /**
     * Shows a confirmation dialog to the user before clearing cookies and restarting the session.
     */
    private void onResetSession() {
        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle("Reset Session?")
                .setMessage("This will clear all session cookies, localStorage for all sites (!) and restart the captcha process. Use this if you are stuck or seeing consistent errors.")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> clearFingerprintCookies())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
