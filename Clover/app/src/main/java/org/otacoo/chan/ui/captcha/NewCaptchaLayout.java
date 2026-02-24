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
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import java.util.Scanner;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * WebView-based layout for handling the new 4chan captcha and Cloudflare Turnstile.
 */
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

    private static final Set<NewCaptchaLayout> visibleInstances = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<String, Long> globalCooldowns = new ConcurrentHashMap<>();
    private static final Map<String, String> globalPayloads = new ConcurrentHashMap<>();
    private static final Map<String, Long> globalExpiries = new ConcurrentHashMap<>();

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

    // Configures basic WebView settings and JS interfaces
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

        setWebViewClient(createCaptchaWebViewClient());
        setBackgroundColor(getAttrColor(getContext(), R.attr.backcolor));
        addJavascriptInterface(new CaptchaInterface(), "CaptchaCallback");
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

        failedFetchAttempts = 0;
        nativeAutoFetchCount = 0;
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
        long now = System.currentTimeMillis();
        int remaining = (int) ((lastRequest + 30000L - now) / 1000);
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
        
        // Preserve active challenge if user returns to it before it expires.
        if (showingActiveCaptcha) {
            Logger.i(TAG, "reset: preserving active captcha challenge view");
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
                if (inner != null) {
                    inner.put("pcd", displayRemaining);
                } else {
                    obj.put("pcd", displayRemaining);
                }
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
        hardReset(false, false);
    }

    // Performs a full reload of the 4chan captcha endpoint
    private void hardReset(boolean afterCooldown, boolean includeTicket) {
        showingActiveCaptcha = false;
        reportedCompletion = false;
        String ticketParam = (includeTicket && !ticket.isEmpty()) ? "&ticket=" + urlEncode(ticket) : "";
        String url = "https://sys.4chan.org/captcha?board=" + board + (thread_id > 0 ? "&thread_id=" + thread_id : "") + ticketParam;
        if (afterCooldown) {
            url += "&_=" + System.currentTimeMillis();
        }
        
        lastResponseWasAsset = false;
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://boards.4chan.org/" + board + "/thread/" + thread_id);
        loadUrl(url, headers);
    }

    // Handles request interception and page lifecycle events
    private WebViewClient createCaptchaWebViewClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("spur.us") || url.contains("mcl.io")) return null;

                boolean isMainFrame = request.isForMainFrame();
                if (isMainFrame && url.contains("sys.4chan.org/")) {
                    hadCloudflareClearanceBeforePageLoad = hasCloudflareClearance();
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
                        : url.contains("sys.4chan.org/captcha?");

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

                if (isCaptchaRequest && isMainFrame && !skipInterceptNextLoad && hasCloudflareClearance()) {
                    return interceptCaptchaRequest(url);
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

                injectThemingAndHooks();

                if (url.endsWith("/post")) {
                    handlePostResult();
                    return;
                }

                if (!url.contains("/captcha")) {
                    handleNonCaptchaRedirect(url);
                    return;
                }

                if (lastResponseWasAsset) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (getWindowToken() != null) onCaptchaLoaded();
                    }, 500);
                } else {
                    extractPayloadFromNativePageAndLoadAsset(view, url);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String host = Uri.parse(url).getHost();
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
        String js = "(function() {" +
                "  var isDark = " + isDark + ";" +
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
                "      var chall = document.getElementById('t-challenge') || {value:''};" +
                "      CaptchaCallback.onCaptchaEntered(chall.value, el.value);" +
                "    } else if (el) setTimeout(pollResp, 500);" +
                "  })();" +
                "})();";
        evaluateJavascript(js, null);
    }

    // Uses OkHttp to fetch captcha data in the background to avoid blank pages
    private WebResourceResponse interceptCaptchaRequest(String url) {
        try {
            String cookies = get4chanCookieHeader();
            if (cookies == null || !cookies.contains("__session")) return null;

            Request request = new Request.Builder()
                    .url(url)
                    .header("Cookie", cookies)
                    .header("Referer", "https://boards.4chan.org/" + board + "/thread/" + thread_id)
                    .header("User-Agent", getSettings().getUserAgentString())
                    .build();

            try (Response response = new OkHttpClient().newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                copyCookies(response);

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
                showOverlay(err, true);
                return;
            }

            // If the user has good standing or verified email, 4chan may skip the captcha challenges.
            String lower = payload.toLowerCase();
            if (lower.contains("verified") || lower.contains("not required")) {
                if (onCooldownNow()) {
                    globalCooldowns.remove(getGlobalKey());
                    cooldownActive = false;
                    if (AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false)) {
                        Toast.makeText(AndroidUtils.getAppContext(), "4chan: Cooldown finished. You can now request a captcha.", Toast.LENGTH_LONG).show();
                    }
                }
                onCaptchaEntered("", "");
                return;
            }

            showOverlay("Tap to request a captcha.", true);
        } catch (Exception e) {
            Logger.e(TAG, "applyPayload failed", e);
        }
    }

    // Schedules a toast notification for when the current captcha session expires
    private void scheduleExpiryNotice(final String key, int seconds) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Long expiryTime = globalExpiries.get(key);
            if (expiryTime != null && expiryTime <= System.currentTimeMillis() + 1000) {
                globalExpiries.remove(key);
                globalPayloads.remove(key);
                if (visibleInstances.contains(this) && AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false)) {
                    Toast.makeText(AndroidUtils.getAppContext(), "4chan: Captcha session expired.", Toast.LENGTH_LONG).show();
                }
            }
        }, seconds * 1000L);
    }

    // Checks for submission errors on the post-reply page
    private void handlePostResult() {
        evaluateJavascript("(function(){ var el = document.getElementById('errmsg'); return el ? el.innerText : ''; })()", result -> {
            String msg = (result != null) ? result.replaceAll("^\"|\"$", "").trim() : "";
            AndroidUtils.runOnUiThread(() -> {
                if (!msg.isEmpty() && !"null".equals(msg)) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                    showOverlay(msg, true);
                } else {
                    hardReset(false, false);
                }
            });
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
    private void extractPayloadFromNativePageAndLoadAsset(WebView view, String url) {
        if (!haveGetCaptchaNavigationDone) haveGetCaptchaNavigationDone = true;

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

                if (!errMsg.isEmpty()) {
                    showOverlay(errMsg, true);
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> extractPayloadFromNativePageAndLoadAsset(this, url), NATIVE_PAYLOAD_RETRY_DELAY_MS);
        } else {
            showOverlay("Captcha failed to load.", true);
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
                        if (candidate.contains("pcd") || candidate.contains("tasks") || candidate.contains("img") || candidate.contains("ticket")) {
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
            return html.replace("__CLOVER_JSON__", json.replace("</script>", "<\\/script>"))
                    .replace("__C_BG__", isDark ? "#0d0d0d" : "#ffffff")
                    .replace("__C_FG__", isDark ? "#e8e8e8" : "#1a1a1a")
                    .replace("__C_TIMER_SHADOW__", isDark ? "0 0 1px rgba(255,255,255,0.25)" : "0 1px 2px rgba(0,0,0,0.15)")
                    .replace("__C_LINK__", isDark ? "#b8b8b8" : "#1565c0")
                    .replace("__C_INPUT_BG__", isDark ? "#1e1e1e" : "#fff")
                    .replace("__C_INPUT_FG__", isDark ? "#e0e0e0" : "#000")
                    .replace("__C_INPUT_BORDER__", isDark ? "#444" : "#ccc")
                    .replace("__C_BTN_BG__", isDark ? "#333" : "#0066cc")
                    .replace("__C_BTN_FG__", isDark ? "#e0e0e0" : "#fff")
                    .replace("__C_BTN_BORDER__", isDark ? "#555" : "#0052a3")
                    .replace("__C_MUTED__", isDark ? "#b0b0b0" : "#555");
        } catch (Exception e) {
            Logger.e(TAG, "Load asset failed", e);
            return null;
        }
    }

    // Injects a non-destructive overlay with a message and action button
    private void showOverlay(String msg, boolean showButton) {
        if (showingActiveCaptcha && !showButton) return;
        boolean isDark = !ThemeHelper.theme().isLightTheme;
        String bg = isDark ? "rgba(18,18,18,0.9)" : "rgba(255,255,255,0.9)";
        String fg = isDark ? "#e0e0e0" : "#000";
        String btn = isDark ? "background:#333;color:#eee;border:1px solid #555;" : "background:#0066cc;color:#fff;border:none;";
        
        String js = "(function(){" +
                "  var ov = document.getElementById('clover-overlay') || document.createElement('div');" +
                "  ov.id = 'clover-overlay';" +
                "  ov.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:9999;background:" + bg + ";color:" + fg + ";display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:sans-serif;text-align:center;padding:20px;box-sizing:border-box;';" +
                "  ov.innerHTML = '<div>" + msg.replace("'", "\\'") + "</div>" + (showButton ? "<button id=\"gcb\" style=\"margin-top:20px;padding:10px 20px;border-radius:4px;" + btn + "\">Get Captcha</button>" : "") + "';" +
                "  document.body.appendChild(ov);" +
                "  var b = document.getElementById('gcb'); if(b) b.onclick = function(){ CaptchaCallback.onRequestCaptcha(); };" +
                "})();";
        evaluateJavascript(js, null);
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
                evaluateJavascript("localStorage.setItem('4chan-tc-ticket','" + t.replace("'", "\\'") + "')", null);
            }
        } catch (Exception ignored) {}
    }

    // Begins background tracking of a new post cooldown timer
    private void startCooldownTracking(int seconds) {
        String key = getGlobalKey();
        globalCooldowns.put(key, System.currentTimeMillis() + (seconds * 1000L));
        if (AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false)) {
            Toast.makeText(AndroidUtils.getAppContext(), "4chan: Cooldown started (" + seconds + "s)", Toast.LENGTH_SHORT).show();
        }
    }

    // Returns true if the WebView already has a valid cf_clearance cookie
    private boolean hasCloudflareClearance() {
        String c = CookieManager.getInstance().getCookie("https://sys.4chan.org");
        return c != null && c.contains("cf_clearance");
    }

    // Aggregates cookies from all 4chan subdomains for background requests
    private String get4chanCookieHeader() {
        CookieManager cm = CookieManager.getInstance();
        Set<String> set = new LinkedHashSet<>();
        for (String b : new String[]{"https://sys.4chan.org", "https://boards.4chan.org"}) {
            String c = cm.getCookie(b);
            if (c != null) set.addAll(Arrays.asList(c.split(";\\s*")));
        }
        return set.isEmpty() ? null : TextUtils.join("; ", set);
    }

    // Syncs cookies from a background OkHttp response back to the WebView
    private void copyCookies(Response r) {
        CookieManager cm = CookieManager.getInstance();
        for (String header : r.headers("Set-Cookie")) {
            cm.setCookie("https://sys.4chan.org", header);
        }
        cm.flush();
    }

    private String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // Handles site-wide errors (like bans) detected during interception
    private void handleSiteErrorInIntercept(String body) {
        String err = body.contains("{") ? extractRawJsonError(body) : null;
        if (err == null && body.contains("<")) err = stripHtml(body);
        if (!TextUtils.isEmpty(err) && err.length() < 500) {
            final String finalErr = err;
            AndroidUtils.runOnUiThread(() -> {
                Toast.makeText(getContext(), finalErr, Toast.LENGTH_LONG).show();
                showOverlay(finalErr, true);
            });
        }
    }

    private static String extractRawJsonError(String b) {
        try { return new JSONObject(b).optString("error", null); } catch (Exception e) { return null; }
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

    @Override public void onDestroy() {
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
        return super.onTouchEvent(event);
    }

    // JavaScript Bridge implementation
    private class CaptchaInterface {
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
            AndroidUtils.runOnUiThread(() -> NewCaptchaLayout.this.hardReset(false, true));
        }

        @JavascriptInterface
        public void onCaptchaPayloadReady(String p) {
            try {
                String decoded = URLDecoder.decode(p, "UTF-8");
                AndroidUtils.runOnUiThread(() -> applyPayload(decoded, getUrl()));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void saveTicket(String t) {
            ticket = t;
        }
    }
}
