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

import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;


public class NewCaptchaLayout extends WebView implements AuthenticationLayoutInterface {
    private static final String TAG = "NewCaptchaLayout";

    /** True when we are showing a captcha UI (asset challenges/legacy image) that would be lost if the user backs out. */
    private volatile boolean showingActiveCaptcha;

    /** True when a post cooldown is active (4chan only). Prevents accidental dismissal. */
    private volatile boolean cooldownActive;

    /** True when we have already reported a solved captcha to the callback for the current session. */
    private boolean reportedCompletion;

    private static final int NATIVE_PAYLOAD_MAX_RETRIES = 5;
    private static final int CLOUDFLARE_MAX_RETRIES = 45;
    private static final int NATIVE_PAYLOAD_RETRY_DELAY_MS = 500;

    private static volatile String ticket = "";

    /** The key used for global 4chan cooldown tracking across boards and threads. */
    private static final String GLOBAL_4CHAN_KEY = "global_4chan_cooldown";
    /** The key used for global tracking of the last "Get Captcha" request time. */
    private static final String LAST_REQUEST_KEY = "last_captcha_request_time";
    /** Handler for showing the periodic background cooldown toast. */
    private static final Handler backgroundToastHandler = new Handler(Looper.getMainLooper());
    /** Current background toast object. */
    private static Toast backgroundToast;
    /** Tracks visible instances to determine when to show background toasts. */
    private static final java.util.Set<NewCaptchaLayout> visibleInstances = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<NewCaptchaLayout, Boolean>());

    private String getGlobalKey() {
        boolean is4chan = (baseUrl != null && baseUrl.contains("4chan.org")) || (site != null && site.name().equalsIgnoreCase("4chan"));
        return is4chan ? GLOBAL_4CHAN_KEY : (board + "_" + thread_id);
    }

    /** Tracks active board-thread cooldowns globally so we can show toasts even when the layout is closed. */
    private static final java.util.Map<String, Long> globalCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks the last known cooldown payload for board-thread so we can restore UI on Back. */
    private static final java.util.Map<String, String> globalPayloads = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks captcha session expiries. */
    private static final java.util.Map<String, Long> globalExpiries = new java.util.concurrent.ConcurrentHashMap<>();

    private AuthenticationLayoutCallback callback;
    private String baseUrl;
    private String board;
    private int thread_id;
    private org.otacoo.chan.core.site.Site site;

    /** True when we served asset HTML from intercept (no #t-root; skip waitForCaptchaForm). */
    private boolean lastResponseWasAsset;
    /** When true, do not intercept the next captcha load so the WebView gets the native 4chan page (e.g. after cooldown). */
    private boolean skipInterceptNextLoad;
    /** True after we have done the first "Get Captcha" navigation (loadUrl); that load sets 4chan-tc-ticket. After that we only use fetch() so we never leave the page. */
    private boolean haveGetCaptchaNavigationDone;
    /** Counter for consecutive failed fetch attempts - after too many failures, fall back to native page load */
    private int failedFetchAttempts = 0;
    /** Track whether we had cf_clearance before the current page load started, to detect when Cloudflare challenge completes */
    private boolean hadCloudflareClearanceBeforePageLoad = false;
    /** Auto-fetch attempts per native page load, capped at 1 to prevent reload loops on pcd=-1. */
    private int nativeAutoFetchCount = 0;

    /** Retry state for extracting payload from the native captcha page without clobbering its DOM (needed for JS challenges). */
    private int nativePayloadRetryAttempts = 0;
    private String nativePayloadRetryUrl = null;


    public NewCaptchaLayout(Context context) {
        super(context);
        commonInit();
    }

    public NewCaptchaLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        commonInit();
    }

    public NewCaptchaLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        commonInit();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void commonInit() {
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // Ensure cookies are accepted and shared across sessions
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this, true);

        // Set a default user agent from settings
        String userAgent = ChanSettings.customUserAgent.get();
        if (!userAgent.isEmpty()) {
            settings.setUserAgentString(userAgent);
        }

        // Use the captcha page client for interception logic
        setWebViewClient(captchaPageClient(null, settings.getUserAgentString()));
        
        setBackgroundColor(getAttrColor(getContext(), R.attr.backcolor));
        addJavascriptInterface(new CaptchaInterface(this), "CaptchaCallback");
    }

    @Override
    public void initialize(Loadable loadable, AuthenticationLayoutCallback callback) {
        this.callback = callback;
        this.reportedCompletion = false;

        this.site = loadable.site;
        SiteAuthentication authentication = loadable.site.actions().postAuthenticate();
        loadable.site.requestModifier().modifyWebView(this);

        this.baseUrl = authentication.baseUrl;
        this.board = loadable.boardCode;
        this.thread_id = loadable.no;

        failedFetchAttempts = 0;
        nativeAutoFetchCount = 0;

        // initialize() is for the captcha placement in the reply layout.
        // Restoration is now handled in the reset() method to avoid duplication.
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public boolean onCooldownNow() {
        String key = getGlobalKey();
        Long endTime = globalCooldowns.get(key);
        return endTime != null && endTime > System.currentTimeMillis();
    }

    /** Returns true if the WebView is currently showing a cooldown message or asset. */
    public boolean isShowingCooldownUI() {
        return cooldownActive;
    }

    public int getCooldownRemainingSeconds() {
        String key = getGlobalKey();
        Long endTime = globalCooldowns.get(key);
        if (endTime == null) return 0;
        return (int) Math.max(0, (endTime - System.currentTimeMillis()) / 1000);
    }

    public int getRequestCooldownRemainingSeconds() {
        Long lastRequest = globalCooldowns.get(LAST_REQUEST_KEY);
        if (lastRequest == null) return 0;
        long now = System.currentTimeMillis();
        int remaining = (int) ((lastRequest + 30000L - now) / 1000);
        return Math.max(0, remaining);
    }

    @Override
    public void reset() {
        showingActiveCaptcha = false;
        reportedCompletion = false;
        
        // Restore cooldown state if tracked globally instead of blindly resetting it
        String key = getGlobalKey();
        
        int remaining = getCooldownRemainingSeconds();
        int requestRemaining = getRequestCooldownRemainingSeconds();
        int displayRemaining = Math.max(remaining, requestRemaining);

        if (displayRemaining > 0) {
            cooldownActive = true;
            showingActiveCaptcha = true;
            boolean darkTheme = !ThemeHelper.theme().isLightTheme;
            
            String savedPayload = globalPayloads.get(key);
            if (savedPayload != null) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(savedPayload);
                    // Update pcd at whichever level the JS will read it from.
                    org.json.JSONObject inner = obj.optJSONObject("twister");
                    if (inner != null) {
                        inner.put("pcd", displayRemaining);
                        obj.put("twister", inner); // re-put to ensure update is serialised
                    } else {
                        obj.put("pcd", displayRemaining);
                    }
                    savedPayload = obj.toString();
                } catch (Exception ignored) {}
            } else {
                // No saved payload, but we have an active timer. Create a mock payload.
                savedPayload = "{\"pcd\":" + displayRemaining + "}";
            }

            Logger.v(TAG, "reset: preserving site-wide cooldown for " + key + " (remaining=" + displayRemaining + "s)");
            String assetHtml = loadAssetWithCaptchaData(savedPayload, darkTheme);
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
        hardReset();
    }

    @Override
    public void hardReset() {
        showingActiveCaptcha = false;
        reportedCompletion = false;
        hardReset(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateInstanceVisibility();
        if (getWindowToken() != null) {
            AndroidUtils.hideKeyboard(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateInstanceVisibility();
    }

    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateInstanceVisibility();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateInstanceVisibility();
    }

    private void updateInstanceVisibility() {
        boolean isNowVisible = isShown() && getWindowVisibility() == View.VISIBLE;
        if (isNowVisible) {
            visibleInstances.add(this);
        } else {
            visibleInstances.remove(this);
        }
        Logger.v(TAG, "updateInstanceVisibility: visible=" + isNowVisible + ", totalVisible=" + visibleInstances.size());
    }

    /** Handle payload from fetch() in page context: decoded from encodeURIComponent; if cooldown inject UI (same page/session), else load asset. */
    private void onCaptchaPayloadFromFetch(String payloadEncoded) {
        String payload = null;
        if (payloadEncoded != null && !payloadEncoded.isEmpty()) {
            try {
                payload = URLDecoder.decode(payloadEncoded, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                Logger.e(TAG, "onCaptchaPayloadFromFetch: decode failed", e);
            }
        }

        if (payload == null || payload.isEmpty()) {
            failedFetchAttempts++;
            if (failedFetchAttempts >= 5) {
                Logger.i(TAG, "onCaptchaPayloadFromFetch: after " + failedFetchAttempts + " failed attempts, falling back to native page load");
                failedFetchAttempts = 0;
                Toast.makeText(getContext(), "Captcha not loading. Switching to native mode...", Toast.LENGTH_LONG).show();
                skipInterceptNextLoad = true;
                hardReset(true, true);
                return;
            }
            Logger.i(TAG, "onCaptchaPayloadFromFetch: no payload (attempt " + failedFetchAttempts + "/5), retrying fetch in 1s");
            new Handler(Looper.getMainLooper()).postDelayed(this::requestCaptchaViaFetch, 1000);
            return;
        }

        // Check if the payload is actually the full HTML page instead of the JSON snippet.
        // If it's HTML, extract the JSON payload from it using our Java parser.
        if (payload.contains("<html") || payload.contains("<!DOCTYPE") || payload.contains("<body")) {
            String extracted = extractTwisterPayload(payload);
            if (extracted != null) {
                payload = extracted;
            } else {
                // If we couldn't find a JSON payload in the HTML, maybe the page is just a challenge or error.
                // We'll treat it as empty and let the retry logic handle it.
                failedFetchAttempts++;
                Logger.w(TAG, "onCaptchaPayloadFromFetch: payload looks like HTML but no postMessage found (attempt " + failedFetchAttempts + "/5). HTML start: " + (payload.length() > 200 ? payload.substring(0, 200) : payload));
                if (failedFetchAttempts >= 5) {
                    failedFetchAttempts = 0;
                    Toast.makeText(getContext(), "Captcha not loading. Switching to native mode...", Toast.LENGTH_LONG).show();
                    skipInterceptNextLoad = true;
                    hardReset(true, true);
                } else {
                    new Handler(Looper.getMainLooper()).postDelayed(this::requestCaptchaViaFetch, 1000);
                }
                return;
            }
        }

        failedFetchAttempts = 0;
        haveGetCaptchaNavigationDone = true;
        boolean darkTheme = !ThemeHelper.theme().isLightTheme;
        
        // Cache the last seen payload for UI restoration
        globalPayloads.put(getGlobalKey(), payload);
        
        // Detect and track captcha expiry
        int expirySecs = getExpiryFromPayload(payload);
        if (expirySecs > 0) {
            long expiryTime = System.currentTimeMillis() + (expirySecs * 1000L);
            globalExpiries.put(getGlobalKey(), expiryTime);
            scheduleExpiryNotice(getGlobalKey(), expirySecs);
        }
        
        int pcdValue = getPcdFromPayload(payload);

        if (pcdValue > 0) {
            Logger.i(TAG, "onCaptchaPayloadFromFetch: pcd=" + pcdValue + " — caching and loading asset with cooldown payload");
            
            // Start the global tracker
            cooldownActive = true;
            startCooldownBackgroundTracking(board, thread_id, pcdValue);
            
            String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
            if (assetHtml == null) {
                skipInterceptNextLoad = true;
                hardReset(true, true);
                return;
            }
            lastResponseWasAsset = true;
            showingActiveCaptcha = true;
            String baseUrl = "https://sys.4chan.org/";
            loadDataWithBaseURL(baseUrl, assetHtml, "text/html", "UTF-8", baseUrl);
            onCaptchaLoaded();
            return;
        }

        if (pcdValue == 0 && payloadHasCaptchaData(payload)) {
            showingActiveCaptcha = true;
            String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
            if (assetHtml == null) {
                skipInterceptNextLoad = true;
                hardReset(true, true);
                return;
            }
            lastResponseWasAsset = true;
            String baseUrl = "https://sys.4chan.org/";
            loadDataWithBaseURL(baseUrl, assetHtml, "text/html", "UTF-8", baseUrl);
            Logger.i(TAG, "onCaptchaPayloadFromFetch: loaded asset with captcha data from fetch (pcd=0)");
            onCaptchaLoaded();
            return;
        }

        if (pcdValue == 0 && !payloadHasCaptchaData(payload)) {
            if (payload.toLowerCase().contains("verification not required") || payload.toLowerCase().contains("verified")) {
                Logger.i(TAG, "onCaptchaPayloadFromFetch: Verification not required, completing.");
                onCaptchaEntered("", "");
                return;
            }
        }

        // Detect explicitly messaged errors.
        String siteError = extract4chanErrorMessage(payload);
        if (siteError != null) {
            Logger.i(TAG, "onCaptchaPayloadFromFetch: site error detected: " + siteError);
            failedFetchAttempts = 0;
            Toast.makeText(getContext(), siteError, Toast.LENGTH_LONG).show();
            injectOverlayUI(!ThemeHelper.theme().isLightTheme, siteError, true);
            onCaptchaLoaded();
            return;
        }

        // Handle error+cd cooldown (e.g. {"error":"You have to wait...","cd":26}).
        // 4chan uses this shape when the user gets a rate-limit instead of the main pcd field.
        int cdValue = getCdFromPayload(payload);
        if (cdValue > 0) {
            Logger.i(TAG, "onCaptchaPayloadFromFetch: error/cd=" + cdValue + " — showing error cooldown UI");
            cooldownActive = true;
            startCooldownBackgroundTracking(board, thread_id, cdValue);
            String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
            if (assetHtml != null) {
                lastResponseWasAsset = true;
                showingActiveCaptcha = true;
                String baseUrl = "https://sys.4chan.org/";
                loadDataWithBaseURL(baseUrl, assetHtml, "text/html", "UTF-8", baseUrl);
                onCaptchaLoaded();
                return;
            }
        }

        // If we get pcd=0 but NO data, or pcd=-1 (invalid), we check if we should retry or show manual UI.

        // pcd field absent entirely but captcha data is present: 4chan returned a ready challenge without
        // a pcd wrapper (common when cooldown just expired). Treat identically to pcd==0 with data.
        if (pcdValue == -1 && payloadHasCaptchaData(payload)) {
            showingActiveCaptcha = true;
            String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
            if (assetHtml == null) {
                skipInterceptNextLoad = true;
                hardReset(true, true);
                return;
            }
            lastResponseWasAsset = true;
            String baseUrl = "https://sys.4chan.org/";
            loadDataWithBaseURL(baseUrl, assetHtml, "text/html", "UTF-8", baseUrl);
            Logger.i(TAG, "onCaptchaPayloadFromFetch: loaded captcha (no pcd field, treated as ready)");
            onCaptchaLoaded();
            return;
        }

        // fetch() returned pcd=-1 with no captcha data.
        //
        // If we are already showing a live native challenge (showingActiveCaptcha=true), this
        // pcd=-1 ping came from the page's own postMessage — do NOT reload. The user needs to
        // stay on the challenge page to solve it. Calling hardReset here would navigate away
        // from the challenge and create an infinite loop: reload → pcd=-1 → reload → …
        if (showingActiveCaptcha) {
            Logger.i(TAG, "onCaptchaPayloadFromFetch: pcd=-1 no data but showingActiveCaptcha=true, ignoring to preserve live challenge page");
            return;
        }

        // JS fetch inside our asset page is not counted as a real 4chan page visit — skip intercept and native reload.
        Logger.i(TAG, "onCaptchaPayloadFromFetch: pcd=-1 no data, doing native reload (skip intercept)");
        failedFetchAttempts = 0;
        skipInterceptNextLoad = true;
        hardReset(true, true);
    }

    /** User tapped "Get Captcha" (or cooldown expired). Does a clean native WebView load — no OkHttp intercept, no JS fetch. */
    private void onGetCaptchaPressed() {
        long now = System.currentTimeMillis();
        Long lastRequest = globalCooldowns.get(LAST_REQUEST_KEY);
        if (lastRequest != null && now < lastRequest + 30000L) {
            int remaining = (int) ((lastRequest + 30000L - now) / 1000);
            if (remaining > 0) {
                Logger.i(TAG, "onGetCaptchaPressed: ignoring request, too soon (" + remaining + "s left)");
                boolean darkTheme = !ThemeHelper.theme().isLightTheme;
                injectOverlayUI(darkTheme, "Please wait " + remaining + "s before requesting another captcha.", true);
                Toast.makeText(getContext(), "Please wait " + remaining + "s before requesting another captcha.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Logger.i(TAG, "onGetCaptchaPressed: native reload (with ticket if available)");
        globalCooldowns.put(LAST_REQUEST_KEY, now);
        nativeAutoFetchCount = 0;  // Allow one auto-fetch attempt for the new native page load
        haveGetCaptchaNavigationDone = true;
        skipInterceptNextLoad = true;
        hardReset(true, true);
    }

    /** Request captcha via OkHttp (same cookies as intercept) so 4chan gets our session/ticket and returns challenges instead of a new cooldown. Runs on background thread, applies result on main thread. */
    private void requestCaptchaViaOkHttp() {
        String ticketParam = (ticket != null && !ticket.isEmpty()) ? "&ticket=" + urlEncode(ticket) : "";
        final String captchaUrl = "https://sys.4chan.org/captcha?board=" + board +
                (thread_id > 0 ? "&thread_id=" + thread_id : "") + "&_=" + System.currentTimeMillis() + ticketParam;
        final String cookieHeader = get4chanCookieHeader();
        final String userAgent = getSettings().getUserAgentString();
        final String referer = "https://boards.4chan.org/" + board + "/thread/" + thread_id;
        new Thread(() -> {
            String payload = null;
            String rawBody = null;
                try {
                    Request.Builder rb = new Request.Builder().url(captchaUrl);
                    if (cookieHeader != null) rb.header("Cookie", cookieHeader);
                    rb.header("Referer", referer);
                    if (userAgent != null && !userAgent.isEmpty()) rb.header("User-Agent", userAgent);
                    rb.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    Response response = new OkHttpClient.Builder().build().newCall(rb.build()).execute();
                    String body = response.body() != null ? response.body().string() : null;
                    copyResponseCookiesToWebView(response, "https://sys.4chan.org");
                    response.close();
                    rawBody = body;
                    if (response.isSuccessful() && body != null && !body.isEmpty()) {
                        payload = extractTwisterPayload(body);
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "requestCaptchaViaOkHttp failed", e);
                    final String errMsg = e.getMessage();
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(AndroidUtils.getAppContext(), "Captcha request error: " + (errMsg != null ? errMsg : "network failure"), Toast.LENGTH_LONG).show());
                }
                final String payloadFinal = payload;
                final String rawBodyFinal = rawBody;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (payloadFinal == null || payloadFinal.isEmpty()) {
                        // Before retrying, check whether 4chan returned a direct JSON error (ban, block, etc.).
                        if (rawBodyFinal != null) {
                            String rawErr = extractRawJsonError(rawBodyFinal);
                            if (rawErr == null) rawErr = extract4chanErrorMessage(rawBodyFinal);
                            if (rawErr == null) rawErr = extractHtmlPageError(rawBodyFinal);
                            if (rawErr != null) {
                                if (isFingerprintGateError(rawErr)) {
                                    // 4chan requires fingerprinting (spur.us/mcl.io) before issuing __session.
                                    // OkHttp cannot run JS, so fall back to a native WebView load where
                                    // the fingerprinting scripts will execute and __session will be stored.
                                    Logger.w(TAG, "requestCaptchaViaOkHttp: fingerprint gate detected, falling back to native load: " + rawErr);
                                    failedFetchAttempts = 0;
                                    skipInterceptNextLoad = true;
                                    hardReset(true, true);
                                    return;
                                }
                                Logger.w(TAG, "requestCaptchaViaOkHttp: site returned an error: " + rawErr);
                                failedFetchAttempts = 0;
                                final String errFinal = rawErr;
                                Toast.makeText(getContext(), errFinal, Toast.LENGTH_LONG).show();
                                injectOverlayUI(!ThemeHelper.theme().isLightTheme, errFinal, true);
                                onCaptchaLoaded();
                                return;
                            }
                        }
                        failedFetchAttempts++;
                        Logger.w(TAG, "requestCaptchaViaOkHttp: no payload (attempt " + failedFetchAttempts + "/5)");
                        if (failedFetchAttempts >= 5) {
                            failedFetchAttempts = 0;
                            Logger.w(TAG, "requestCaptchaViaOkHttp: Too many failures. Fallback to native load (no intercept).");
                            Toast.makeText(getContext(), "Captcha request failed after multiple attempts. Retrying...", Toast.LENGTH_LONG).show();
                            skipInterceptNextLoad = true;
                            hardReset(true, true);
                            return;
                        }
                        new Handler(Looper.getMainLooper()).postDelayed(this::requestCaptchaViaOkHttp, 1000);
                        return;
                    }
                    failedFetchAttempts = 0;
                    persistTicketFromPayload(payloadFinal);
                    applyCaptchaPayload(payloadFinal);
                });
        }).start();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    /** Apply a captcha payload (from OkHttp or fetch): load asset for cooldown or challenges, or inject "request captcha" UI. Must run on main thread. */
    private void applyCaptchaPayload(String payload) {
        boolean darkTheme = !ThemeHelper.theme().isLightTheme;
        int pcdValue = getPcdFromPayload(payload);
        if (pcdValue > 0) {
            showingActiveCaptcha = false;
            cooldownActive = true;
            globalPayloads.put(getGlobalKey(), payload);
            startCooldownBackgroundTracking(board, thread_id, pcdValue);
            String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
            if (assetHtml == null) {
                Logger.w(TAG, "applyCaptchaPayload: failed to load asset HTML");
                skipInterceptNextLoad = true;
                hardReset(true, true);
                return;
            }
            lastResponseWasAsset = true;
            loadDataWithBaseURL("https://sys.4chan.org/", assetHtml, "text/html", "UTF-8", "https://sys.4chan.org/");
            onCaptchaLoaded();
            return;
        }
        if (!payloadHasCaptchaData(payload)) {
            // If the payload explicitly says verification is not required, we can finish immediately.
            if (payload.toLowerCase().contains("verification not required") || payload.toLowerCase().contains("verified")) {
                Logger.i(TAG, "applyCaptchaPayload: Verification not required found in payload, completing.");
                onCaptchaEntered("", "");
                return;
            }

            showingActiveCaptcha = false;
            cooldownActive = false;
            injectRequestCaptchaUI(darkTheme, true);
            onCaptchaLoaded();
            return;
        }

        // We have actual captcha data (legacy image or native tasks).
        showingActiveCaptcha = true;
        cooldownActive = false;
        String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
        if (assetHtml != null) {
            lastResponseWasAsset = true;
            loadDataWithBaseURL("https://sys.4chan.org/", assetHtml, "text/html", "UTF-8", "https://sys.4chan.org/");
        }
        onCaptchaLoaded();
    }

    /** Request captcha via fetch() from current page (same origin, credentials). Kept for fallback; after cooldown we use requestCaptchaViaOkHttp so cookies are sent. */
    private void requestCaptchaViaFetch() {
        final String captchaUrl = "https://sys.4chan.org/captcha?board=" + board +
                (thread_id > 0 ? "&thread_id=" + thread_id : "") + "&_=" + System.currentTimeMillis();
        String escUrl = captchaUrl.replace("\\", "\\\\").replace("'", "\\'");
        String escTicket = (ticket != null ? ticket : "").replace("\\", "\\\\").replace("'", "\\'");
        
        // Use a trivial fetch script with enhanced browser-like headers.
        String js = "(function(){\n"
            + "  var u='" + escUrl + "';\n"
            + "  var jt='" + escTicket + "';\n"
            + "  try{\n"
            + "    var t=localStorage.getItem('4chan-tc-ticket');\n"
            + "    if(!t && jt) { t=jt; try{localStorage.setItem('4chan-tc-ticket',jt);}catch(e){} }\n"
            + "    if(t && t !== 'null') u+='&ticket='+encodeURIComponent(t);\n"
            + "  }catch(e){}\n"
            + "  fetch(u,{\n"
            + "    credentials:'include',\n"
            + "    headers:{\n"
            + "      'X-Requested-With':'XMLHttpRequest',\n"
            + "      'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',\n"
            + "      'Sec-Fetch-Dest':'empty',\n"
            + "      'Sec-Fetch-Mode':'cors',\n"
            + "      'Sec-Fetch-Site':'same-origin'\n"
            + "    }\n"
            + "  })\n"
            + "    .then(r => r.text())\n"
            + "    .then(h => {\n"
            + "      try {\n"
            + "        var enc = encodeURIComponent(h);\n"
            + "        CaptchaCallback.onCaptchaPayloadReady(enc);\n"
            + "      } catch(e) { CaptchaCallback.onCaptchaPayloadReady(''); }\n"
            + "    })\n"
            + "    .catch(e => { try { CaptchaCallback.onCaptchaPayloadReady(''); } catch(ex){} });\n"
            + "})();";
        evaluateJavascript(js, null);
    }

    private void hardReset(boolean afterCooldown) {
        hardReset(afterCooldown, false);
    }

    // Hard reload the native captcha page.
    private void hardReset(boolean afterCooldown, boolean includeTicketParam) {
        showingActiveCaptcha = false;
        String ticketParam = (includeTicketParam && ticket != null && !ticket.isEmpty()) ? "&ticket=" + urlEncode(ticket) : "";
        String captchaUrl = "https://sys.4chan.org/captcha?board=" + board +
                (thread_id > 0 ? "&thread_id=" + thread_id : "") + ticketParam;
        if (afterCooldown) {
            captchaUrl = captchaUrl + "&_=" + System.currentTimeMillis();
        }
        final String loadUrlFinal = captchaUrl;
        lastResponseWasAsset = false;
        String userAgent = getSettings().getUserAgentString();
        setWebViewClient(captchaPageClient(loadUrlFinal, userAgent));
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", "https://boards.4chan.org/" + board + "/thread/" + thread_id);
        loadUrl(captchaUrl, headers);
    }

    // WebViewClient: intercept captcha HTML, extract payload, serve new_captcha.html asset.
    private WebViewClient captchaPageClient(final String captchaUrl, final String userAgent) {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String requestUrl = request.getUrl().toString();
                boolean isMainFrame = request.isForMainFrame();

                // Allow fingerprinting service scripts to load and execute.
                // mcl.spur.us/mcl.js needs to run to set __session before 4chan can serves captchas.
                if (requestUrl.contains("spur.us") || requestUrl.contains("mcl.io")) {
                    Logger.i(TAG, "shouldInterceptRequest: allowing fingerprint service request: " + requestUrl);
                    return null;
                }
                
                // Track cf_clearance state at start of main frame requests to sys.4chan.org
                boolean hasCf = hasCloudflareClearance();
                if (isMainFrame && requestUrl.contains("sys.4chan.org/")) {
                    hadCloudflareClearanceBeforePageLoad = hasCf;
                }

                // Match captcha requests: usually /captcha?board=... or /captcha?action=...
                // If board is set, we use a more specific match to avoid intercepting other things
                boolean isCaptchaRequest;
                if (board != null && !board.isEmpty()) {
                    String core = "https://sys.4chan.org/captcha?board=" + board;
                    isCaptchaRequest = requestUrl.startsWith(core);
                } else {
                    isCaptchaRequest = requestUrl.contains("sys.4chan.org/captcha?");
                }
                
                // Only intercept main-frame navigations. JS fetch()/XHR sub-resource requests must
                // pass through to the real servers so that requestCaptchaViaFetch() receives the
                // actual 4chan JSON response via onCaptchaPayloadReady — not our asset HTML.
                //
                // IMPORTANT: when showingActiveCaptcha=true the native 4chan challenge page is
                // live and running its own JS. Any navigation it triggers (self-refresh to load
                // the actual puzzle) must NOT be intercepted — doing so would replace the challenge
                // page with our asset and loop back to "0s" state. Let it load natively and let
                // onPageFinished / extractPayloadFromNativePageAndLoadAsset handle the result.
                boolean isCaptchaRequest = (board != null && !board.isEmpty()) 
                        ? url.startsWith("https://sys.4chan.org/captcha?board=" + board)
                if (isMainFrame && isCaptchaRequest && showingActiveCaptcha) {
                    // Reset per-page state so that the incoming page is evaluated fresh.
                    showingActiveCaptcha = false;
                    nativeAutoFetchCount = 0;
                    lastResponseWasAsset = false;
                    // Keep skipInterceptNextLoad=true so this re-navigation (triggered by mcl.js /
                    // fingerprinting completing) also loads natively. This gives the JS time to work.
                    skipInterceptNextLoad = true;
                    return null;
                }
                if (isCaptchaRequest && hasCf && !skipInterceptNextLoad && isMainFrame) {
                    try {
                        String cookieHeader = get4chanCookieHeader();
                        // Debug: log which session-critical cookies are present
                        boolean hasSession = cookieHeader != null && cookieHeader.contains("__session");
                        boolean hasCfBm    = cookieHeader != null && cookieHeader.contains("__cf_bm");
                        boolean hasCfuvid  = cookieHeader != null && cookieHeader.contains("_cfuvid");
                        Logger.i(TAG, "shouldInterceptRequest: OkHttp captcha fetch — __session=" + hasSession
                                + " __cf_bm=" + hasCfBm + " _cfuvid=" + hasCfuvid);
                        // If fingerprinting cookies are still absent, skip OkHttp and let the
                        // WebView load natively so mcl.js can run and set them first.
                        if (!hasSession) {
                            Logger.w(TAG, "shouldInterceptRequest: __session not yet set — deferring to native load");
                            return null;
                        }
                        // If we don't have board/thread, use a generic referer
                        String referer = (board != null && thread_id > 0) 
                            ? "https://boards.4chan.org/" + board + "/thread/" + thread_id
                            : "https://sys.4chan.org/";
                        
                        Request.Builder rb = new Request.Builder().url(requestUrl);
                        if (cookieHeader != null) rb.header("Cookie", cookieHeader);
                        rb.header("Referer", referer);
                        if (userAgent != null && !userAgent.isEmpty()) rb.header("User-Agent", userAgent);
                        rb.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        Response response = new OkHttpClient.Builder().build().newCall(rb.build()).execute();
                        String body = response.body() != null ? response.body().string() : null;
                        copyResponseCookiesToWebView(response, "https://sys.4chan.org");
                        response.close();
                        
                        if (response.isSuccessful() && body != null && !body.isEmpty()) {
                            String payload = extractTwisterPayload(body);
                            if (payload != null) {
                                persistTicketFromPayload(payload);
                                haveGetCaptchaNavigationDone = true;
                                boolean darkTheme = !ThemeHelper.theme().isLightTheme;
                                
                                // Cache the payload and check for cooldown pcd
                                globalPayloads.put(getGlobalKey(), payload);
                                
                                int expirySecs = getExpiryFromPayload(payload);
                                if (expirySecs > 0) {
                                    long expiryTime = System.currentTimeMillis() + (expirySecs * 1000L);
                                    globalExpiries.put(getGlobalKey(), expiryTime);
                                    scheduleExpiryNotice(getGlobalKey(), expirySecs);
                                }
                                
                                int pcdValue = getPcdFromPayload(payload);
                                if (pcdValue > 0) {
                                    startCooldownBackgroundTracking(board, thread_id, pcdValue);
                                    cooldownActive = true;
                                }

                                // If pcd==0 but we have no captcha data, avoid getting stuck in a loop of
                                // intercept -> load asset with no data -> pcd=0 -> intercept
                                if (pcdValue == 0 && !payloadHasCaptchaData(payload)) {
                                    return null;
                                }
                                
                                String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
                                if (assetHtml != null) {
                                    lastResponseWasAsset = true;
                                    showingActiveCaptcha = true;
                                    return new WebResourceResponse("text/html", "UTF-8",
                                            new ByteArrayInputStream(assetHtml.getBytes(StandardCharsets.UTF_8)));
                                }
                            } else {
                                // No twister payload found — check if body is a direct JSON error or HTML error page (ban, blocked, fingerprint check, etc.).
                                String rawErr = extractRawJsonError(body);
                                if (rawErr == null) rawErr = extractHtmlPageError(body);
                                if (rawErr != null) {
                                    if (isFingerprintGateError(rawErr)) {
                                        // 4chan is requiring fingerprinting via spur.us/mcl.io.
                                        Logger.w(TAG, "shouldInterceptRequest: fingerprint gate detected, falling back to native load: " + rawErr);
                                        return null;
                                    }
                                    Logger.w(TAG, "shouldInterceptRequest: 4chan returned an error: " + rawErr);
                                    final String errFinal = rawErr;
                                    final boolean dark = !ThemeHelper.theme().isLightTheme;
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        Toast.makeText(AndroidUtils.getAppContext(), errFinal, Toast.LENGTH_LONG).show();
                                        injectOverlayUI(dark, errFinal, true);
                                        onCaptchaLoaded();
                                    });
                                    // Serve a blank page so the WebView does not load the raw JSON again.
                                    return new WebResourceResponse("text/html", "UTF-8",
                                            new ByteArrayInputStream("<html><body></body></html>".getBytes(StandardCharsets.UTF_8)));
                                }
                            }
                        }
                    } catch (Exception e) {
                        Logger.e(TAG, "Intercept captcha failed", e);
                    }
                }
                
                // If we get here it means we are loading natively or intercept failed
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

                // Post submission result page: e.g. https://sys.4chan.org/b/post
                // 4chan puts an error message in #errmsg when the post fails (wrong captcha, ban, etc.).
                Uri parsedForPost = Uri.parse(url);
                String postPath = parsedForPost.getPath() != null ? parsedForPost.getPath() : "";
                if (postPath.endsWith("/post")) {
                    view.evaluateJavascript(
                        "(function(){" +
                        "  var el = document.getElementById('errmsg');" +
                        "  return el ? (el.textContent || el.innerText || '').trim() : '';" +
                        "})()",
                        result -> {
                            if (result == null) return;
                            // evaluateJavascript returns a JSON-encoded string literal.
                            String msg = result.replaceAll("^\"|\"$", "").trim();
                            if (!msg.isEmpty() && !msg.equals("null")) {
                                Logger.w(TAG, "onPageFinished: post page #errmsg: " + msg);
                                AndroidUtils.runOnUiThread(() -> {
                                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                                    if (getWindowToken() != null) {
                                        injectOverlayUI(!ThemeHelper.theme().isLightTheme, msg, true);
                                        onCaptchaLoaded();
                                    }
                                });
                            } else {
                                // No specific error - go back to captcha so the user can retry.
                                AndroidUtils.runOnUiThread(() -> { if (getWindowToken() != null) hardReset(false, false); });
                            }
                        }
                    );
                    return;
                }

                boolean isCaptchaUrl = url.contains("/captcha");
                Logger.i(TAG, "onPageFinished: url=" + url + " isCaptchaUrl=" + isCaptchaUrl
                        + " lastResponseWasAsset=" + lastResponseWasAsset
                        + " showingActiveCaptcha=" + showingActiveCaptcha
                        + " skipInterceptNextLoad=" + skipInterceptNextLoad);
                
                // NOTE: do NOT clear lastResponseWasAsset unconditionally here; we need it
                // below to distinguish our own loadDataWithBaseURL("https://sys.4chan.org/", asset)
                // from a real navigation to the 4chan home page.
                
                CookieManager.getInstance().flush();
                
                boolean hasCloudflareNow = hasCloudflareClearance();
                if (!hadCloudflareClearanceBeforePageLoad && hasCloudflareNow) {
                    hadCloudflareClearanceBeforePageLoad = true;
                    if (isCaptchaUrl) {
                        Logger.i(TAG, "cf_clearance set, reloading captcha page: " + url);
                        loadUrl(url);
                    } else if (!lastResponseWasAsset) {
                        // Only redirect when we haven't just loaded our own asset HTML.
                        Logger.i(TAG, "cf_clearance set on non-captcha URL, redirecting back to captcha");
                        hardReset();
                    }
                    return;
                }
                
                // Don't interfere with verification pages, but if we somehow landed on the home page,
                // redirect back to captcha - UNLESS we are showing our own asset (loaded via
                // loadDataWithBaseURL with baseUrl="https://sys.4chan.org/") which fires
                // onPageFinished with this same root URL.
                if (!isCaptchaUrl) {
                    Uri parsed = Uri.parse(url);
                    String path = parsed.getPath();
                    if (path == null || path.equals("/") || path.isEmpty()) {
                        if (lastResponseWasAsset) {
                            // This onPageFinished is for our own asset page — not a real home-page
                            // redirect.  Don't call hardReset(); just finalise the load.
                            Logger.v(TAG, "onPageFinished: asset base URL fired, not redirecting");
                            onCaptchaLoaded();
                            return;
                        }
                        Logger.i(TAG, "Landed on 4chan home page, redirecting back to captcha");
                        hardReset();
                        return;
                    }
                    lastResponseWasAsset = false;
                    onCaptchaLoaded();
                    return;
                }
                
                if (lastResponseWasAsset) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (view.getWindowToken() == null) return;
                        installPostMessageHook();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (view.getWindowToken() == null) return;
                            onCaptchaLoaded();
                        }, 500);
                    }, 500);
                } else {
                    extractPayloadFromNativePageAndLoadAsset(view, url);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String host = Uri.parse(url).getHost();
                if (host == null) return false;
                if (host.contains("4chan.org") || host.contains("cloudflare")) {
                    return false;
                }
                AndroidUtils.openLink(url);
                return true;
            }
        };
    }

    private boolean hasCloudflareClearance() {
        CookieManager.getInstance().flush();  // Ensure cookies are written
        String cookies = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        boolean hasClearance = cookies != null && cookies.contains("cf_clearance");
        Logger.i(TAG, "hasCloudflareClearance: " + hasClearance + " cookies: " + (cookies != null ? cookies.substring(0, Math.min(200, cookies.length())) : "null"));
        return hasClearance;
    }

    // Build Cookie header from WebView's CookieManager for 4chan domains.
    private String get4chanCookieHeader() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush(); // Ensure __session, __cf_bm, _cfuvid written by JS are readable
        Set<String> parts = new LinkedHashSet<>();
        for (String base : new String[]{"https://sys.4chan.org", "https://boards.4chan.org", "https://www.4chan.org"}) {
            String c = cm.getCookie(base);
            if (c != null && !c.isEmpty()) {
                parts.addAll(Arrays.asList(c.split(";\\s*")));
            }
        }
        return parts.isEmpty() ? null : android.text.TextUtils.join("; ", parts);
    }

    // Copy Set-Cookie headers from OkHttp response into WebView CookieManager so reply POST sends same session.
    private static void copyResponseCookiesToWebView(Response response, String cookieUrl) {
        try {
            CookieManager cm = CookieManager.getInstance();
            for (int i = 0; i < response.headers().size(); i++) {
                String name = response.headers().name(i);
                if (name != null && "Set-Cookie".equalsIgnoreCase(name)) {
                    String value = response.headers().value(i);
                    if (value != null && !value.isEmpty()) {
                        cm.setCookie(cookieUrl, value);
                    }
                }
            }
            cm.flush();
        } catch (Exception e) {
            Logger.e(TAG, "Copy captcha cookies to WebView failed", e);
        }
    }

    // Extract the twister/captcha JSON from 4chan HTML
    private static String extractTwisterPayload(String html) {
        if (html == null) return null;
        
        // If the HTML clearly contains a Cloudflare/Turnstile challenge, we should NOT extract a partial payload.
        // Doing so might lead us to serve a "pcd:0" asset that covers the actual challenge widget
        if (html.contains("cf-turnstile") || html.contains("challenges.cloudflare.com") || html.contains("cf-browser-verification")) {
            return null;
        }

        // Find all occurrences of postMessage and pick the longest one that looks like a valid payload.
        // This handles cases where the page sends multiple status messages (e.g. pcd:0 then later the actual data).
        String bestPayload = null;
        int lastIndex = -1;
        while ((lastIndex = html.indexOf("postMessage", lastIndex + 1)) != -1) {
            int start = html.indexOf("{", lastIndex);
            if (start < 0) continue;
            
            int depth = 1;
            int i = start + 1;
            while (i < html.length() && depth > 0) {
                char c = html.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            
            if (depth == 0) {
                String candidate = html.substring(start, i).trim();
                
                // Strict validation: must be valid JSON and contain actionable fields.
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(candidate);
                    org.json.JSONObject data = obj.optJSONObject("twister");
                    if (data == null) data = obj;
                    
                    // A valid payload must have pcd, ticket, captcha-specific data (img/tasks),
                    // OR an error+cd cooldown response (e.g. {"error":"...","cd":26}).
                    boolean hasPcd = data.has("pcd");
                    boolean hasTicket = data.has("ticket") && !data.isNull("ticket");
                    boolean hasAssets = data.has("img") || data.has("tasks");
                    boolean hasErrorCd = data.has("error") || data.has("cd");
                    
                    if (hasPcd || hasTicket || hasAssets || hasErrorCd) {
                        // Prefer the one with assets or ticket.
                        if (bestPayload == null || hasAssets || hasTicket) {
                            bestPayload = candidate;
                        }
                    }
                } catch (Exception e) {
                    // Not valid JSON
                }
            }
        }
        
        return bestPayload;
    }

    // Load asset captcha/new_captcha.html with __CLOVER_JSON__ and __C_*__ color placeholders replaced (valid CSS; ternary in CSS is invalid).
    private String loadAssetWithCaptchaData(String jsonPayload, boolean darkTheme) {
        try {
            InputStream is = getContext().getAssets().open("captcha/new_captcha.html");
            String html = new java.util.Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
            is.close();
            // JSON lives in a <script type="application/json"> tag, so only </script> needs escaping.
            String escaped = jsonPayload.replace("</script>", "<\\/script>");
            html = html.replace("__CLOVER_JSON__", escaped);
            if (darkTheme) {
                html = html.replace("__C_BG__", "#0d0d0d");
                html = html.replace("__C_FG__", "#e8e8e8");
                html = html.replace("__C_TIMER_SHADOW__", "0 0 1px rgba(255,255,255,0.25)");
                html = html.replace("__C_LINK__", "#b8b8b8");
                html = html.replace("__C_INPUT_BG__", "#1e1e1e");
                html = html.replace("__C_INPUT_FG__", "#e0e0e0");
                html = html.replace("__C_INPUT_BORDER__", "#444");
                html = html.replace("__C_BTN_BG__", "#333");
                html = html.replace("__C_BTN_FG__", "#e0e0e0");
                html = html.replace("__C_BTN_BORDER__", "#555");
                html = html.replace("__C_MUTED__", "#b0b0b0");
            } else {
                html = html.replace("__C_BG__", "#ffffff");
                html = html.replace("__C_FG__", "#1a1a1a");
                html = html.replace("__C_TIMER_SHADOW__", "0 1px 2px rgba(0,0,0,0.15)");
                html = html.replace("__C_LINK__", "#1565c0");
                html = html.replace("__C_INPUT_BG__", "#fff");
                html = html.replace("__C_INPUT_FG__", "#000");
                html = html.replace("__C_INPUT_BORDER__", "#ccc");
                html = html.replace("__C_BTN_BG__", "#0066cc");
                html = html.replace("__C_BTN_FG__", "#fff");
                html = html.replace("__C_BTN_BORDER__", "#0052a3");
                html = html.replace("__C_MUTED__", "#555");
            }
            return html;
        } catch (Exception e) {
            Logger.e(TAG, "Load new_captcha.html failed", e);
            return null;
        }
    }

    // Native 4chan page often renders blank in WebView. Extract twister payload from document and load our asset.
    private void extractPayloadFromNativePageAndLoadAsset(final WebView view, final String url) {
        installPostMessageHook();

        // Reset retry counter if URL changed.
        if (nativePayloadRetryUrl == null || !nativePayloadRetryUrl.equals(url)) {
            nativePayloadRetryUrl = url;
            nativePayloadRetryAttempts = 0;
        }
        // Mark that we've done the first GetCaptcha navigation (native page load that sets 4chan-tc-ticket).
        // After this, we only use fetch() to get new captcha, never leaving the page.
        if (!haveGetCaptchaNavigationDone) {
            haveGetCaptchaNavigationDone = true;
            Logger.i(TAG, "extractPayloadFromNativePageAndLoadAsset: set haveGetCaptchaNavigationDone=true (first native page load with ticket)");
        }
        // Log whether 4chan set the ticket (helps debug captcha not loading)
        evaluateJavascript("(function(){ try { var t = localStorage.getItem('4chan-tc-ticket'); return t ? 'set' : 'not set'; } catch(e) { return 'error'; } })();",
                ticketStatus -> Logger.i(TAG, "localStorage 4chan-tc-ticket: " + ticketStatus));

        // Collect HTML, any JS-side payload, the solved captcha response (if already filled by
        // 4chan's own JS in #t-resp), and any error message already on the page in #errmsg.
        String checkJs = "(function(){" +
                "  var res = { html: document.documentElement.outerHTML };" +
                "  try {" +
                "    if (window.pcd_payload) res.payload = window.pcd_payload;" +
                "    else if (window.t_pcd) res.payload = { pcd: window.t_pcd };" +
                "    else {" +
                "      var txt = document.body.innerText;" +
                "      var m = txt.match(/(\\d+)\\s*(s|seconds|sec)/i);" +
                "      if (m) res.inferredPcd = parseInt(m[1]);" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    var tRespEl = document.getElementById('t-resp');" +
                "    if (tRespEl) {" +
                "      if (tRespEl.value) {" +
                "        res.tResp = tRespEl.value;" +
                "        var challEl = document.getElementById('t-challenge') || document.querySelector('[name=\"t-challenge\"]');" +
                "        res.tChall = challEl ? challEl.value : '';" +
                "      } else {" +
                "        var p = tRespEl.parentElement;" +
                "        if (p) {" +
                "          var errParts = [];" +
                "          for (var c = p.firstChild; c; c = c.nextSibling) {" +
                "            if (c === tRespEl) continue;" +
                "            var tag = (c.tagName || '').toUpperCase();" +
                "            if (tag === 'INPUT' || tag === 'SCRIPT' || tag === 'STYLE') continue;" +
                "            var t = (c.textContent || c.innerText || '').trim();" +
                "            if (t) errParts.push(t);" +
                "          }" +
                "          var tRespAreaText = errParts.join(' ').trim();" +
                "          if (tRespAreaText) res.tRespArea = tRespAreaText;" +
                "        }" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    var errEl = document.getElementById('errmsg');" +
                "    if (errEl) res.errMsg = (errEl.textContent || errEl.innerText || '').trim();" +
                "  } catch(e) {}" +
                "  return res;" +
                "})()";

        evaluateJavascript(checkJs, rawResult -> {
            AndroidUtils.runOnUiThread(() -> {
                        boolean darkTheme = !ThemeHelper.theme().isLightTheme;
                        if (rawResult == null || rawResult.isEmpty() || rawResult.equals("null")) {
                            Logger.w(TAG, "extractPayloadFromNativePage: got null JS result");
                            return;
                        }

                        String html = null;
                        String payload = null;
                        int inferredPcd = -1;
                        String tResp = null;
                        String tChall = null;
                        String pageErrMsg = null;
                        String tRespArea = null;

                        try {
                            org.json.JSONObject res = new org.json.JSONObject(rawResult);
                            html = res.optString("html");
                            if (res.has("payload")) {
                                Object p = res.get("payload");
                                if (p instanceof org.json.JSONObject) {
                                    payload = p.toString();
                                } else if (p instanceof String) {
                                    payload = (String) p;
                                }
                            }
                            inferredPcd = res.optInt("inferredPcd", -1);
                            String tr = res.optString("tResp", "");
                            if (!tr.isEmpty()) tResp = tr;
                            String tc = res.optString("tChall", "");
                            if (!tc.isEmpty()) tChall = tc;
                            String em = res.optString("errMsg", "");
                            if (!em.isEmpty()) pageErrMsg = em;
                            String ta = res.optString("tRespArea", "");
                            if (!ta.isEmpty()) tRespArea = ta;
                        } catch (Exception e) {
                            Logger.e(TAG, "extractPayloadFromNativePage: failed to parse JS result. Length: " + rawResult.length(), e);
                            // Fallback if it's just raw HTML returned somehow
                            html = rawResult;
                        }

                        // If 4chan's own JS already solved/filled the captcha response, grab it now.
                        if (tResp != null && !tResp.isEmpty()) {
                            Logger.i(TAG, "extractPayloadFromNativePage: #t-resp filled, calling onCaptchaEntered");
                            onCaptchaEntered(tChall != null ? tChall : "", tResp);
                            return;
                        }

                        // #t-resp is present but empty with text alongside it: 4chan is blocking
                        // posting (IP range ban, anti-spam, etc.). Show the message as-is.
                        if (tRespArea != null && !tRespArea.isEmpty()) {
                            String cleanTRespArea = stripHtml(tRespArea);
                            Logger.w(TAG, "extractPayloadFromNativePage: blocking message near #t-resp: " + cleanTRespArea);
                            nativePayloadRetryAttempts = 0;
                            nativePayloadRetryUrl = null;
                            if (isFingerprintGateError(cleanTRespArea)) {
                                // The fingerprinting JS (spur.us/mcl.io) is still running.
                                Logger.i(TAG, "extractPayloadFromNativePage: fingerprint gate in #t-resp area, keeping page alive for JS to complete");
                                showingActiveCaptcha = true;
                                onCaptchaLoaded();
                                return;
                            }
                            Toast.makeText(getContext(), cleanTRespArea, Toast.LENGTH_LONG).show();
                            injectOverlayUI(darkTheme, cleanTRespArea, false);
                            onCaptchaLoaded();
                            return;
                        }

                        // If an error message is already displayed on the captcha page, show it.
                        if (pageErrMsg != null && !pageErrMsg.isEmpty()) {
                            Logger.w(TAG, "extractPayloadFromNativePage: #errmsg on captcha page: " + pageErrMsg);
                            nativePayloadRetryAttempts = 0;
                            nativePayloadRetryUrl = null;
                            Toast.makeText(getContext(), pageErrMsg, Toast.LENGTH_LONG).show();
                            injectOverlayUI(darkTheme, pageErrMsg, true);
                            onCaptchaLoaded();
                            return;
                        }

                        if (html == null || html.isEmpty()) {
                            installPostMessageHook();
                            waitForCaptchaForm();
                            return;
                        }

                        Logger.i(TAG, "extractPayloadFromNativePage: HTML checked (len=" + html.length() + ")");
                        
                        // Use payload from JS environment if available, otherwise try to extract from HTML
                        if (payload == null) {
                            payload = extractTwisterPayload(html);
                        }

                        // Detect explicitly messaged errors in either payload or the raw HTML.
                        String siteError = extract4chanErrorMessage(payload != null ? payload : html);
                        if (siteError != null) {
                            String cleanSiteError = stripHtml(siteError);
                            Logger.i(TAG, "extractPayloadFromNativePage: site error detected: " + cleanSiteError);
                            if (isFingerprintGateError(cleanSiteError)) {
                                // The page itself is the fingerprinting gate. mcl.js has not run yet.
                                Logger.i(TAG, "extractPayloadFromNativePage: fingerprint gate page — keeping alive for JS to complete");
                                showingActiveCaptcha = true;
                                skipInterceptNextLoad = true;
                                onCaptchaLoaded();
                                return;
                            }
                            nativePayloadRetryAttempts = 0;
                            nativePayloadRetryUrl = null;
                            Toast.makeText(getContext(), cleanSiteError, Toast.LENGTH_LONG).show();
                            injectOverlayUI(!ThemeHelper.theme().isLightTheme, cleanSiteError, true);
                            onCaptchaLoaded();
                            return;
                        }

                        if (payload != null) {
                            nativePayloadRetryAttempts = 0;
                            nativePayloadRetryUrl = null;
                            persistTicketFromPayload(payload);
                            
                            globalPayloads.put(getGlobalKey(), payload);
                            int expirySecs = getExpiryFromPayload(payload);
                            if (expirySecs > 0) {
                                long expiryTime = System.currentTimeMillis() + (expirySecs * 1000L);
                                globalExpiries.put(getGlobalKey(), expiryTime);
                                scheduleExpiryNotice(getGlobalKey(), expirySecs);
                            }

                            int pcdValue = getPcdFromPayload(payload);
                            Logger.i(TAG, "extractPayloadFromNativePage: found payload, pcd=" + pcdValue);
                            if (pcdValue > 0) {
                                startCooldownBackgroundTracking(board, thread_id, pcdValue);
                                String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
                                if (assetHtml != null) {
                                    lastResponseWasAsset = true;
                                    showingActiveCaptcha = true;
                                    loadDataWithBaseURL(url, assetHtml, "text/html", "UTF-8", url);
                                    onCaptchaLoaded();
                                    return;
                                }
                            }

                            // Handle error+cd cooldown (e.g. {"error":"...","cd":26}) from the native page.
                            int cdValue = getCdFromPayload(payload);
                            if (cdValue > 0) {
                                Logger.i(TAG, "extractPayloadFromNativePage: error/cd=" + cdValue + " — showing error cooldown UI");
                                cooldownActive = true;
                                startCooldownBackgroundTracking(board, thread_id, cdValue);
                                String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
                                if (assetHtml != null) {
                                    lastResponseWasAsset = true;
                                    showingActiveCaptcha = true;
                                    loadDataWithBaseURL(url, assetHtml, "text/html", "UTF-8", url);
                                    onCaptchaLoaded();
                                    return;
                                }
                            }
                            
                            // pcd=0 or no pcd: cooldown over. Only replace document when we have actual captcha; else inject "request captcha" UI to preserve page/session/ticket.
                            if (payloadHasCaptchaData(payload)) {
                                Logger.i(TAG, "extractPayloadFromNativePage: detected active captcha data, caching and loading asset");
                                globalPayloads.put(getGlobalKey(), payload);
                                String assetHtml = loadAssetWithCaptchaData(payload, darkTheme);
                                if (assetHtml != null) {
                                    lastResponseWasAsset = true;
                                    showingActiveCaptcha = true;
                                    loadDataWithBaseURL(url, assetHtml, "text/html", "UTF-8", url);
                                    onCaptchaLoaded();
                                    return;
                                }
                            }

                            // Auto-trigger fetch once if we have a ticket; capped at 1 to prevent reload loops.
                            if (ticket != null && !ticket.isEmpty() && nativeAutoFetchCount < 1) {
                                nativeAutoFetchCount++;
                                Logger.i(TAG, "extractPayloadFromNativePage: pcd=0/-1 and have ticket, auto-triggering fetch (attempt " + nativeAutoFetchCount + ")");
                                requestCaptchaViaFetch();
                                return;
                            }

                            // Auto-fetch exhausted (or no ticket). Preserve live JS challenges; otherwise show "Get Captcha" UI.
                            boolean isLiveChallenge = html != null && (
                                    html.contains("id=\"t-root\"") ||
                                    html.contains("id=\"t-msg\"") ||
                                    html.contains("cf-turnstile") ||
                                    html.contains("challenges.cloudflare.com") ||
                                    html.contains("spur.us") ||
                                    (html.contains("Challenge") && html.contains(" of ")));
                            if (isLiveChallenge) {
                                Logger.i(TAG, "extractPayloadFromNativePage: live challenge page detected after exhausting auto-fetch, showing for user interaction");
                                showingActiveCaptcha = true;
                                skipInterceptNextLoad = true; // let 4chan's own navigation through the interceptor
                                onCaptchaLoaded();
                                return;
                            }

                            injectRequestCaptchaUI(darkTheme, haveGetCaptchaNavigationDone);
                            onCaptchaLoaded();
                            return;
                        } else if (inferredPcd > 0) {
                            Logger.i(TAG, "extractPayloadFromNativePage: inferred cooldown from HTML text: " + inferredPcd + "s. Caching as visual payload.");
                            // Manual background tracking for pure HTML cooldowns
                            cooldownActive = true;
                            startCooldownBackgroundTracking(board, thread_id, inferredPcd);
                            
                            // Create a minimal payload for visual cooldown display.
                            try {
                                String mockPayload = "{\"pcd\":" + inferredPcd + "}";
                                globalPayloads.put(getGlobalKey(), mockPayload);
                                
                                String assetHtml = loadAssetWithCaptchaData(mockPayload, darkTheme);
                                if (assetHtml != null) {
                                    lastResponseWasAsset = true;
                                    loadDataWithBaseURL(url, assetHtml, "text/html", "UTF-8", url);
                                    onCaptchaLoaded();
                                    return;
                                }
                            } catch (Exception ignored) {}
                            
                            showingActiveCaptcha = true;
                            onCaptchaLoaded();
                            return;
                        }
                        
                        // Fallback to original markers check if still no payload
                        Logger.w(TAG, "extractPayloadFromNativePage: no payload found. Checking for markers...");
                        boolean isCloudflare = html.contains("cf-turnstile") || html.contains("challenges.cloudflare.com") || html.contains("cf-browser-verification");
                        boolean isSpur = html.contains("spur.us") || html.contains("spur-input");
                        boolean isTwister = html.contains("id=\"t-root\"") || html.contains("id=\"t-msg\"") || (html.contains("Challenge") && html.contains("of"));
                            int maxRetries = (isCloudflare || isSpur || isTwister) ? CLOUDFLARE_MAX_RETRIES : NATIVE_PAYLOAD_MAX_RETRIES;

                            if (isCloudflare || isSpur || isTwister) {
                                Logger.i(TAG, (isCloudflare ? "Cloudflare" : (isSpur ? "Spur" : "Twister")) + " detected, calling onCaptchaLoaded so user can see/solve challenge.");
                                showingActiveCaptcha = true;
                                onCaptchaLoaded();
                                return;
                            } else {
                                // If not a known challenge, show a message while retrying so the user knows what's happening.
                                injectOverlayUI(darkTheme, "Loading captcha data... (" + nativePayloadRetryAttempts + "/" + maxRetries + ")", false);
                            }

                            // Don't clobber the page immediately: mcl/spur challenges need the DOM intact to run.
                            // Retry a few times; postMessage hook may also fire asynchronously.
                            if (nativePayloadRetryAttempts < maxRetries) {
                                nativePayloadRetryAttempts++;
                                Logger.i(TAG, "extractPayloadFromNativePage: retrying native payload extraction (" + nativePayloadRetryAttempts + "/" + maxRetries + ")");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (view.getWindowToken() == null) return;
                                    extractPayloadFromNativePageAndLoadAsset(view, url);
                                }, NATIVE_PAYLOAD_RETRY_DELAY_MS);
                                return;
                            }

                            // Still nothing after retries. Provide a manual retry button.
                            Logger.w(TAG, "extractPayloadFromNativePage: giving up after retries; injecting manual retry UI");
                            Toast.makeText(getContext(), "Captcha failed to load. Tap \"Get Captcha\" to try again.", Toast.LENGTH_LONG).show();
                            injectRequestCaptchaUI(darkTheme, haveGetCaptchaNavigationDone);
                            onCaptchaLoaded();
                    });
            });
    }

    // True if payload contains actual captcha (image or tasks), not just cooldown/request message.
    private static boolean payloadHasCaptchaData(String payload) {
        return payload != null && (payload.contains("\"img\"") || payload.contains("\"tasks\""));
    }

    // Extract 4chan error message from payload or HTML, which may be wrapped in a twister object or be raw HTML.
    private static String unwrapTwisterPayload(String payload) {
        if (payload == null || payload.isEmpty()) return payload;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(payload);
            org.json.JSONObject twister = obj.optJSONObject("twister");
            return twister != null ? twister.toString() : payload;
        } catch (Exception e) {
            return payload;
        }
    }

    private void startCooldownBackgroundTracking(final String board, final int thread_id, int seconds) {
        if (board == null || board.isEmpty()) return;
        if (seconds <= 0) return;

        final boolean is4chan = (baseUrl != null && baseUrl.contains("4chan.org")) || (site != null && site.name().equalsIgnoreCase("4chan"));
        final String key = getGlobalKey();
        final long now = System.currentTimeMillis();
        final long scheduledEndTime = now + (seconds * 1000L);

        // Avoid toast spam if we are already tracking this specific cooldown (within 2s tolerance).
        Long existingEnd = globalCooldowns.get(key);
        boolean isNewCooldown = (existingEnd == null || Math.abs(existingEnd - scheduledEndTime) > 2000 || now > existingEnd);

        globalCooldowns.put(key, scheduledEndTime);
        if (!isNewCooldown) {
            return;
        }

        final boolean toastsEnabled = AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false);

        if (toastsEnabled && is4chan) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(AndroidUtils.getAppContext(), "4chan: Cooldown started (" + seconds + "s)", Toast.LENGTH_SHORT).show());
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Long actualEndTime = globalCooldowns.get(key);
                long localNow = System.currentTimeMillis();
                // If actualEndTime was updated significantly, this runnable represents a stale session.
                if (actualEndTime != null && Math.abs(actualEndTime - scheduledEndTime) < 2000) {
                    if (actualEndTime <= localNow + 1500) {
                        globalCooldowns.remove(key);
                        if (toastsEnabled) {
                            String fMsg = is4chan ? "4chan: Cooldown finished. You can now request a captcha."
                                                : ("4chan: Cooldown for /" + board + "/ " + (thread_id > 0 ? "thread " + thread_id : "board") + " finished.");
                            Toast.makeText(AndroidUtils.getAppContext(), fMsg, Toast.LENGTH_LONG).show();
                        }
                    }
                }
        }, seconds * 1000L);
    }

    private void scheduleExpiryNotice(final String key, int seconds) {
        if (seconds <= 0) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Long expiryTime = globalExpiries.get(key);
            if (expiryTime != null && expiryTime <= System.currentTimeMillis() + 1000) {
                globalExpiries.remove(key);
                globalPayloads.remove(key);
                ticket = "";
                if (visibleInstances.isEmpty()
                        && AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false)) {
                    Toast.makeText(AndroidUtils.getAppContext(), "4chan: Captcha session expired.", Toast.LENGTH_LONG).show();
                }
            }
        }, seconds * 1000L);
    }

    private static int getPcdFromPayload(String payload) {
        String data = unwrapTwisterPayload(payload);
        if (data == null) {
            return -1;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(data);
            if (!obj.has("pcd")) return -1;
            int pcd = obj.optInt("pcd", 0);
            Logger.i(TAG, "getPcdFromPayload: extracted pcd=" + pcd);
            return pcd;
        } catch (Exception e) {
            Logger.w(TAG, "getPcdFromPayload: JSON parse failed for snippet: " + (data.length() > 50 ? data.substring(0, 50) : data));
            return -1;
        }
    }

    private static int getCdFromPayload(String payload) {
        String data = unwrapTwisterPayload(payload);
        if (data == null) return -1;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(data);
            return obj.optInt("cd", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int getExpiryFromPayload(String payload) {
        String data = unwrapTwisterPayload(payload);
        if (data == null) return -1;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(data);
            // 4chan returns the captcha lifetime in "ttl" (seconds). Fall back to "expiry" for any
            // older/alternative formats.
            int ttl = obj.optInt("ttl", -1);
            return ttl >= 0 ? ttl : obj.optInt("expiry", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    // Extract error message from 4chan's payload, which may be wrapped in HTML or a twister object. Suppress pure cooldown messages since the UI already shows them.
    private String extract4chanErrorMessage(String payload) {
        if (payload == null || payload.isEmpty()) return null;

        // If the input looks like HTML, try to extract JSON from a <pre> tag first.
        // The Android WebView wraps raw JSON responses in <html><body><pre>…</pre></body></html>.
        String jsonStr = payload.trim();
        if (jsonStr.startsWith("<") || jsonStr.toLowerCase().contains("<html")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<pre[^>]*>(.*?)</pre>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(jsonStr);
            jsonStr = m.find() ? m.group(1).trim() : null;
        }

        if (jsonStr != null && !jsonStr.isEmpty()) {
            try {
                org.json.JSONObject data = new org.json.JSONObject(jsonStr);
                // Unwrap twister wrapper if present.
                org.json.JSONObject inner = data.optJSONObject("twister");
                if (inner != null) data = inner;
                String err = data.optString("error", null);
                if (err != null && !err.isEmpty()) {
                    // Suppress only genuine timer-backed cooldowns; the countdown UI already
                    // shows them. Any other error (ban, IP restriction, etc.) is surfaced as-is.
                    boolean isPureCooldown = data.optInt("pcd", -1) > 0 || data.optInt("cd", -1) > 0;
                    if (!isPureCooldown) {
                        return err;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // If the above fails, try to extract any error message from the raw HTML body (e.g. if 4chan's server returns an error page instead of JSON).
    private static String extractRawJsonError(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            String trimmed = body.trim();
            if (!trimmed.startsWith("{")) return null;
            org.json.JSONObject obj = new org.json.JSONObject(trimmed);
            String err = obj.optString("error", null);
            if (err != null && !err.isEmpty()) return err;
        } catch (Exception ignored) {}
        return null;
    }

    // Returns true if the error message is 4chan's fingerprinting gate (spur.us / mcl.io).
    private static boolean isFingerprintGateError(String err) {
        if (err == null) return false;
        String lower = err.toLowerCase(java.util.Locale.ENGLISH);
        return lower.contains("spur.us") || lower.contains("mcl.io") || lower.contains("unblock");
    }

    // Strip HTML tags and excessive whitespace for cleaner display of error messages that may contain formatting.
    static String stripHtml(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static String extractHtmlPageError(String body) {
        if (body == null || body.isEmpty()) return null;
        String trimmed = body.trim();
        if (!trimmed.startsWith("<")) return null;

        String text = trimmed.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

        if (!text.isEmpty() && text.length() < 400) {
            return text;
        }
        return null;
    }

    private void persistTicketFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(payload);
            org.json.JSONObject data = obj.optJSONObject("twister");
            if (data == null) data = obj;
            
            // Check for ticket in either twister object or top-level.
            if (data == null || !data.has("ticket")) {
                Logger.w(TAG, "persistTicketFromPayload: payload has no ticket field");
                return;
            }
            
            Object t = data.opt("ticket");
            String ticketStr = (t == null || data.isNull("ticket")) ? null : t.toString();
            if (ticketStr != null && !ticketStr.isEmpty()) {
                ticket = ticketStr;
                String esc = ticketStr.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", " ");
                final String escFinal = esc;
                AndroidUtils.runOnUiThread(() -> {
                    // Also store in localStorage so 4chan's own scripts can find it if they reload.
                    evaluateJavascript("try{localStorage.setItem('4chan-tc-ticket','" + escFinal + "');}catch(e){}", null);
                    Logger.i(TAG, "persistTicketFromPayload: set 4chan-tc-ticket from server response: " + ticketStr.substring(0, Math.min(ticketStr.length(), 8)) + "...");
                });
            } else {
                ticket = "";
                AndroidUtils.runOnUiThread(() -> {
                    evaluateJavascript("try{localStorage.removeItem('4chan-tc-ticket');}catch(e){}", null);
                    Logger.i(TAG, "persistTicketFromPayload: removed 4chan-tc-ticket (falsy in response)");
                });
            }
        } catch (Exception e) {
            Logger.e(TAG, "persistTicketFromPayload failed", e);
        }
    }

    /** Inject message + Get Captcha button into current page (preserving scripts/DOM). @param afterCooldown if false, show neutral text. */
    private void injectRequestCaptchaUI(boolean darkTheme, boolean afterCooldown) {
        injectOverlayUI(darkTheme, afterCooldown ? "Cooldown expired. You can now request a captcha." : "Tap the button below to load the captcha.", true);
    }

    /** Helper to inject a non-destructive overlay with a message and optional button. */
    private void injectOverlayUI(boolean darkTheme, String msg, boolean showButton) {
        if (showingActiveCaptcha) {
            Logger.i(TAG, "injectOverlayUI: already showing active captcha, skipping overlay injection");
            return;
        }

        // Determine the initial countdown for the button based on 4chan-issued cooldown only
        long now = System.currentTimeMillis();
        int initialCountdown = 0;
        
        // Check pcd/cd cooldown issued by 4chan
        Long cooldownEnd = globalCooldowns.get(getGlobalKey());
        if (cooldownEnd != null && cooldownEnd > now) {
            // Add a 1s buffer to ensure 4chan's server-side clock has definitely rolled over.
            initialCountdown = Math.max(initialCountdown, (int)((cooldownEnd - now) / 1000) + 1);
        }

        // Semi-transparent background so challenges underneath can be seen if they appear.
        String bg = darkTheme ? "rgba(18,18,18,0.85)" : "rgba(255,255,255,0.85)";
        String fg = darkTheme ? "#e0e0e0" : "#000";
        String btnStyle = darkTheme ? "padding:12px 24px;margin-top:16px;font-size:16px;cursor:pointer;background:#333;color:#e0e0e0;border:1px solid #555;border-radius:6px;" : "padding:12px 24px;margin-top:16px;font-size:16px;cursor:pointer;background:#0066cc;color:#fff;border:none;border-radius:6px;";
        String escMsg = msg.replace("\\", "\\\\").replace("'", "\\'");

        // Use a non-destructive overlay instead of replacing body.innerHTML.
        // This preserves native scripts (like Cloudflare/Turnstile) that might be running.
        String js = "(function(){"
                + "if(document.querySelector('[src*=\"challenges.cloudflare.com\"]') || document.querySelector('.cf-turnstile')) return;"
                + "var msg='" + escMsg + "',bg='" + bg + "',fg='" + fg + "',btnStyle='" + btnStyle.replace("'", "\\'") + "';"
                + "var ic = " + initialCountdown + ";"
                + "var ov = document.getElementById('clover-overlay');"
                + "if (!ov) {"
                + "  ov = document.createElement('div');"
                + "  ov.id = 'clover-overlay';"
                + "  document.body.appendChild(ov);"
                + "}"
                + "ov.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;background:'+bg+';color:'+fg+';display:flex;flex-direction:column;align-items:center;justify-content:flex-start;padding-top:20%;font-family:sans-serif;text-align:center;padding-left:24px;padding-right:24px;box-sizing:border-box;';"
                + "ov.innerHTML = '<div style=\"margin-bottom:20px;font-weight:bold;font-size:18px;line-height:1.4;\">'+msg+'</div>" + (showButton ? "<button type=\"button\" id=\"gcb\" style=\"'+btnStyle+'\">Get Captcha</button>" : "") + "';"
                + (showButton ? "var btn = document.getElementById('gcb');"
                + "function setBtn(s) { if(s<=0){ btn.disabled=false; btn.innerText=\"Get Captcha\"; } else { btn.disabled=true; btn.innerText=\"Get Captcha (\"+s+\"s)\"; } }"
                + "setBtn(ic);"
                + "if(ic > 0) {"
                + "  var t=setInterval(function(){ ic--; setBtn(ic); if(ic<=0) clearInterval(t); }, 1000);"
                + "}"
                + "btn.onclick = function(){ "
                + "  this.disabled = true; this.innerText = \"Loading...\"; "
                + "  if(typeof CaptchaCallback!=='undefined'&&CaptchaCallback.onRequestCaptcha) CaptchaCallback.onRequestCaptcha(); "
                + "  else if(typeof CaptchaCallback!=='undefined'&&CaptchaCallback.onCooldownExpired) CaptchaCallback.onCooldownExpired(); "
                + "};" : "")
                + "})();";
        evaluateJavascript(js, null);
    }

    private void installPostMessageHook() {
        String hookCode = "(function() {" +
                "  if (window.__postMessageHookInstalled) return;" +
                "  window.__postMessageHookInstalled = true;" +
                // Poll #t-resp so we catch it when 4chan's JS fills the hidden input with the solved captcha response.
                "  if (!window.__tRespPollStarted) {" +
                "    window.__tRespPollStarted = true;" +
                "    (function poll() {" +
                "      var el = document.getElementById('t-resp');" +
                "      if (el) {" +
                "        if (el.value) {" +
                "          var challEl = document.getElementById('t-challenge') || document.querySelector('[name=\"t-challenge\"]');" +
                "          try { CaptchaCallback.onCaptchaEntered(challEl ? challEl.value : '', el.value); } catch(e) {}" +
                "          return;" +
                "        }" +
                "        var p = el.parentElement;" +
                "        if (p) {" +
                "          var parts = [];" +
                "          for (var c = p.firstChild; c; c = c.nextSibling) {" +
                "            if (c === el) continue;" +
                "            var tag = (c.tagName || '').toUpperCase();" +
                "            if (tag === 'INPUT' || tag === 'SCRIPT' || tag === 'STYLE') continue;" +
                "            var t = (c.textContent || c.innerText || '').trim();" +
                "            if (t) parts.push(t);" +
                "          }" +
                "          var errText = parts.join(' ').trim();" +
                "          if (errText) { try { CaptchaCallback.onSiteError(errText); } catch(e) {} return; }" +
                "        }" +
                "        setTimeout(poll, 300);" +
                "      }" +
                "    })();" +
                "  }" +
                "  var original = window.parent.postMessage.bind(window.parent);" +
                "  window.parent.postMessage = function(msg, origin) {" +
                "    if (msg && typeof msg === 'object' && msg.twister) {" +
                "      var t = msg.twister;"+
            // Only call onCaptchaEntered when a real challenge was solved (non-empty challenge AND non-empty response).
            "      try { if (t.challenge && t.response) { CaptchaCallback.onCaptchaEntered(t.challenge, t.response); } } catch (e) {}" +
            "      try {" +
            "        var hasPayload = (t && (t.tasks !== undefined || t.img !== undefined || t.pcd !== undefined || t.ticket !== undefined));" +
            "        if (hasPayload) {" +
            "          var json = JSON.stringify(msg);" +
            "          var enc = encodeURIComponent(json);" +
            "          CaptchaCallback.onCaptchaPayloadReady(enc);" +
            "        }" +
            "      } catch (e) {}" +
                "    }" +
                "    return original(msg, origin);" +
                "  };" +
                "})();";
        evaluateJavascript(hookCode, null);
    }

    private void waitForCaptchaForm() {
        String waitCode = "(function() {" +
                "  var attempts = 0;" +
                "  function checkForForm() {" +
                "    attempts++;" +
                "    var hasTRoot = !!document.getElementById('t-root');" +
                "    var hasTMsg = !!document.getElementById('t-msg');" +
                "    if (hasTRoot || hasTMsg) {" +
                "      CaptchaCallback.onCaptchaLoaded();" +
                "    } else if (attempts < 50) {" +
                "      setTimeout(checkForForm, 100);" +
                "    } else {" +
                "      CaptchaCallback.onCaptchaLoaded();" +
                "    }" +
                "  }" +
                "  checkForForm();" +
                "})();";
        evaluateJavascript(waitCode, null);
    }

    @Override
    public boolean requireResetAfterComplete() {
        return false;
    }

    @Override
    public void onDestroy() {
        showingActiveCaptcha = false;
        visibleInstances.remove(this);
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {}
    }

    /** IME is never shown: captcha only needs taps, no text input. */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return null;
    }
    // Stale for new captc
    @Override
    public boolean onTouchEvent(MotionEvent event){
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // captcha is 300px x 150px, and I scale it to the 75% of the screen
            // therefore the height is the 37.5% of the screen
            float realHeight = getMeasuredWidth() * 0.375f;
            // don't let the user swipe the screen if he's touching the part
            // of the screen where the captcha actually is
            if (event.getY() < realHeight) {
                // there be dragons
                ViewParent disallowHere = this;
                while (disallowHere != null) {
                    disallowHere.requestDisallowInterceptTouchEvent(true);
                    disallowHere = disallowHere.getParent();
                }
            }
        }
        return super.onTouchEvent(event);
    }

    private void onCaptchaLoaded() {
        requestFocus();
        AndroidUtils.hideKeyboard(this);
    }

    private void onCaptchaEntered(String challenge, String response) {
        String key = getGlobalKey();
        globalCooldowns.remove(key);
        cooldownActive = false;
        if (reportedCompletion) return;
        reportedCompletion = true;
        showingActiveCaptcha = false;
        if (callback != null) {
            callback.onAuthenticationComplete(this, challenge, response);
        }
    }
    public static class CaptchaInterface {
        private final NewCaptchaLayout layout;

        public CaptchaInterface(NewCaptchaLayout layout) {
            this.layout = layout;
        }

        @JavascriptInterface
        public void onCaptchaLoaded() {
            AndroidUtils.runOnUiThread(layout::onCaptchaLoaded);
        }

        @JavascriptInterface
        public void onCaptchaEntered(final String challenge, final String response) {
            AndroidUtils.runOnUiThread(() -> layout.onCaptchaEntered(challenge, response));
        }

        @JavascriptInterface
        public void onCooldownExpired() {
            AndroidUtils.runOnUiThread(() -> {
                globalCooldowns.remove(layout.getGlobalKey());
                layout.cooldownActive = false;
                layout.onGetCaptchaPressed();
            });
        }

        @JavascriptInterface
        public void onRequestCaptcha() {
            AndroidUtils.runOnUiThread(layout::onGetCaptchaPressed);
        }

        @JavascriptInterface
        public void onCaptchaPayloadReady(final String payloadBase64) {
            AndroidUtils.runOnUiThread(() -> layout.onCaptchaPayloadFromFetch(payloadBase64));
        }

        @JavascriptInterface
        public void onSiteError(final String message) {
            AndroidUtils.runOnUiThread(() -> {
                if (message == null || message.isEmpty()) return;
                String cleanMsg = stripHtml(message);
                Logger.w(NewCaptchaLayout.TAG, "CaptchaInterface.onSiteError: " + cleanMsg);
                if (isFingerprintGateError(cleanMsg)) {
                    // Fingerprinting JS is still running and will set __session on its own.
                    // Keep the page alive so it can complete; do not show a fatal overlay.
                    Logger.i(NewCaptchaLayout.TAG, "CaptchaInterface.onSiteError: fingerprint gate, keeping page alive");
                    layout.showingActiveCaptcha = true;
                    layout.onCaptchaLoaded();
                    return;
                }
                Toast.makeText(layout.getContext(), cleanMsg, Toast.LENGTH_LONG).show();
                layout.injectOverlayUI(!ThemeHelper.theme().isLightTheme, cleanMsg, false);
                layout.onCaptchaLoaded();
            });
        }

        @JavascriptInterface
        public void saveTicket(final String ticket) {
            AndroidUtils.runOnUiThread(() -> {
                NewCaptchaLayout.ticket = ticket;
                try {
                    String esc = ticket == null ? "" : ticket
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", " ");
                    layout.evaluateJavascript(
                            "try{localStorage.setItem('4chan-tc-ticket','" + esc + "');}catch(e){}",
                            null
                    );
                } catch (Exception ignored) {}
            });
        }
    }
}
