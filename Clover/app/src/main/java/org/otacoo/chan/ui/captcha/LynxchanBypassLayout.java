package org.otacoo.chan.ui.captcha;

import static org.otacoo.chan.Chan.inject;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.LinearInterpolator;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import javax.inject.Inject;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles the 8chan block-bypass captcha flow.
 *
 * Flow:
 *   1. hardReset() fetches GET {baseUrl}captcha.js (no boardUri — bypass captcha, not per-post)
 *   2. Shows the JPEG image; user types the answer
 *   3. On submit, POSTs to {baseUrl}blockBypass.js?json=1 with captchaId + captchaAnswer
 *   4. On {"status":"ok"}, the server sets the bypass cookie; calls onAuthenticationComplete("","")
 */
public class LynxchanBypassLayout extends LinearLayout implements AuthenticationLayoutInterface {
    private static final String TAG = "LynxchanBypassLayout";

    @Inject OkHttpClient okHttpClient;

    private AuthenticationLayoutCallback callback;
    private String baseUrl;

    private ImageView captchaImage;
    private EditText captchaInput;
    private Button refreshButton;
    private Button submitButton;
    private TextView statusText;

    private String currentCaptchaId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ObjectAnimator refreshSpinner;

    public LynxchanBypassLayout(Context context) {
        this(context, null);
    }

    public LynxchanBypassLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("this-escape")
    public LynxchanBypassLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inject(this);

        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.layout_lynxchan_captcha, this, true);

        captchaImage  = findViewById(R.id.lynxchan_captcha_image);
        captchaInput  = findViewById(R.id.lynxchan_captcha_input);
        submitButton  = findViewById(R.id.lynxchan_captcha_submit);
        refreshButton = findViewById(R.id.lynxchan_captcha_refresh);
        statusText    = findViewById(R.id.lynxchan_captcha_status);

        submitButton.setOnClickListener(v -> submit());
        captchaImage.setOnClickListener(v -> hardReset());

        captchaInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submit();
                return true;
            }
            return false;
        });
    }

    @Override
    public void initialize(Loadable loadable, AuthenticationLayoutCallback callback) {
        this.callback = callback;
        SiteAuthentication auth = loadable.site.actions().postAuthenticate();
        this.baseUrl = auth.baseUrl;
        if (!this.baseUrl.endsWith("/")) this.baseUrl += "/";
    }

    @Override
    public void reset() {
        hardReset();
    }

    @Override
    public void hardReset() {
        currentCaptchaId = null;
        captchaInput.setText("");
        captchaImage.setImageBitmap(null);
        setStatus("Loading bypass captcha…");
        submitButton.setEnabled(false);

        // Remove any stale captchaid from the java.net store so the server
        // is forced to issue a fresh one via Set-Cookie rather than reusing
        // a consumed/expired id (which would cause valid:false on submit).
        clearCaptchaId();

        // No boardUri — this is the site-wide bypass captcha, not a per-board captcha.
        new Thread(() -> fetchCaptcha(baseUrl + "captcha.js")).start();
    }

    private void clearCaptchaId() {
        java.net.CookieManager jcm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
        if (jcm != null) {
            try {
                java.net.CookieStore store = jcm.getCookieStore();
                for (java.net.URI uri : new java.util.ArrayList<>(store.getURIs())) {
                    if (uri.getHost() != null && uri.getHost().contains("8chan")) {
                        for (java.net.HttpCookie c : new java.util.ArrayList<>(store.get(uri))) {
                            if ("captchaid".equals(c.getName())) {
                                store.remove(uri, c);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // Also clear from WebView on the main thread
        mainHandler.post(() -> {
            try {
                android.webkit.CookieManager wvcm = android.webkit.CookieManager.getInstance();
                for (String d : new String[]{"https://8chan.moe/", "https://8chan.st/"}) {
                    wvcm.setCookie(d, "captchaid=; Max-Age=0; Path=/");
                }
                wvcm.flush();
            } catch (Exception ignored) {}
        });
    }

    private void fetchCaptcha(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("Accept", "image/jpeg,image/*,*/*")
                    .build();

            try (Response resp = okHttpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    showError("Captcha fetch failed: HTTP " + resp.code());
                    return;
                }

                // Log all Set-Cookie headers from captcha.js for diagnostics.
                for (String sc : resp.headers("Set-Cookie")) {
                    Logger.d(TAG, "captcha.js Set-Cookie: " + sc);
                }

                // 1. Read captchaId from Set-Cookie response header.
                String captchaId = null;
                for (String setCookie : resp.headers("Set-Cookie")) {
                    if (setCookie.startsWith("captchaid=")) {
                        captchaId = setCookie.substring("captchaid=".length()).split(";")[0].trim();
                        break;
                    }
                }

                // 2. Fallback: java.net store — OkHttp's AppCookieJar writes here from
                //    saveFromResponse. Prefer this over WebView since it reflects the
                //    current OkHttp session, not a potentially stale browser session.
                if (captchaId == null) {
                    java.net.CookieManager jcm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
                    if (jcm != null) {
                        try {
                            java.util.List<java.net.HttpCookie> list =
                                    jcm.getCookieStore().get(new java.net.URI(url));
                            for (java.net.HttpCookie hc : list) {
                                if ("captchaid".equals(hc.getName()) && !hc.getValue().isEmpty()) {
                                    captchaId = hc.getValue();
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // 3. Last resort: WebView CookieManager.
                if (captchaId == null) {
                    try {
                        String rawCookies = android.webkit.CookieManager.getInstance().getCookie(url);
                        if (rawCookies != null) {
                            for (String part : rawCookies.split(";\\s*")) {
                                if (part.startsWith("captchaid=")) {
                                    captchaId = part.substring("captchaid=".length()).trim();
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (captchaId == null) {
                    showError("No captcha ID in server response.");
                    return;
                }

                byte[] imgBytes = resp.body().bytes();
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) {
                    showError("Could not decode captcha image.");
                    return;
                }

                final String finalId = captchaId;
                final Bitmap finalBmp = bmp;
                Logger.i(TAG, "fetchCaptcha: using captchaId=" + finalId);
                mainHandler.post(() -> {
                    currentCaptchaId = finalId;
                    captchaImage.setImageBitmap(finalBmp);
                    setStatus("Posting requires a block bypass (proof-of-work).\nPlease solve the captcha.");
                    submitButton.setEnabled(true);
                    captchaInput.requestFocus();
                    AndroidUtils.requestKeyboardFocus(captchaInput);
                });
            }
        } catch (Exception e) {
            Logger.e(TAG, "fetchCaptcha error", e);
            showError("Captcha error: " + e.getMessage());
        }
    }

    private void submit() {
        String answer = captchaInput.getText().toString().trim();
        if (answer.isEmpty()) {
            setStatus("Please enter the captcha text");
            return;
        }
        if (currentCaptchaId == null) {
            setStatus("Captcha not ready — please wait or tap \u21ba");
            return;
        }
        AndroidUtils.hideKeyboard(captchaInput);
        submitButton.setEnabled(false);
        setStatus("Submitting bypass\u2026");

        final String captchaId = currentCaptchaId;
        new Thread(() -> submitBypass(captchaId, answer)).start();
    }

    private void submitBypass(String captchaId, String answer) {
        try {
            // DO NOT call syncCookiesToJar here. The WebView still carries the stale
            // POW_TOKEN and captchaexpiration from the previous session; syncing them
            // into java.net would overwrite the fresh values that OkHttp obtained from
            // captcha.js, causing either a Varnish 403 (stale POW_TOKEN) or a server-
            // side captcha-session mismatch (stale captchaexpiration).

            String cookieSnap = org.otacoo.chan.core.di.NetModule.getRawCookieHeader(baseUrl);
            Logger.i(TAG, "submitBypass: captchaId=" + captchaId + " answerLen=" + answer.length()
                    + " cookies=" + cookieSnap);

            // renewBypass.js reads req.body.captcha only.
            // The captchaId is already in the Cookie header via the java.net store.
            Logger.i(TAG, "submitBypass POST fields: captcha=" + answer + " -> renewBypass.js");
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("captcha", answer)
                    .build();

            Request req = new Request.Builder()
                    .url(baseUrl + "renewBypass.js?json=1")
                    .post(body)
                    .build();

            try (Response resp = okHttpClient.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                Logger.d(TAG, "renewBypass: HTTP " + resp.code() + " body=" + respBody);
                for (String sc : resp.headers("Set-Cookie")) {
                    Logger.i(TAG, "renewBypass Set-Cookie: " + sc);
                }
                if (!resp.isSuccessful()) {
                    String msg = resp.code() == 403
                            ? "Proof of work expired \u2014 session will refresh automatically."
                            : "Bypass failed: HTTP " + resp.code();
                    showError(msg);
                    mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
                    return;
                }

                // Some Lynxchan versions return an HTML success page.
                if (respBody.contains("<title>Captcha solved.</title>")) {
                    storeCookiesFromResponse(resp);
                    org.otacoo.chan.core.site.sites.chan8.Chan8PowNotifier.onPowSolved();
                    Logger.i(TAG, "Bypass established via HTML success page");
                    mainHandler.post(() -> callback.onAuthenticationComplete(this, "", ""));
                    return;
                }

                JSONObject json = new JSONObject(respBody);
                String status = json.optString("status", "");

                if ("error".equals(status)) {
                    String msg = json.optString("data", "Wrong captcha answer.");
                    showError(msg);
                    mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
                    return;
                }

                if ("ok".equals(status)) {
                    // 8chan returns {"status":"ok","data":null} and sets a bypass cookie
                    // that embeds a PBKDF2-SHA512 proof-of-work challenge.
                    // Cookie structure: 24-char hex ID + 344-char base64 session + base64 hash target.
                    // If hasHashPart (length > 368), POW is required to activate the bypass.
                    String bypassCookieValue = null;
                    for (String sc : resp.headers("Set-Cookie")) {
                        if (sc.startsWith("bypass=")) {
                            bypassCookieValue = sc.substring("bypass=".length()).split(";")[0].trim();
                            break;
                        }
                    }
                    Logger.i(TAG, "renewBypass ok, bypassCookieLen="
                            + (bypassCookieValue != null ? bypassCookieValue.length() : "null"));

                    // Cookie > 368 chars means it has a hash target → needs POW.
                    if (bypassCookieValue != null && bypassCookieValue.length() > 368) {
                        setStatus("Please wait, solving proof of work\u2026 \nThis may take a few minutes.");
                        runPowAndValidate(captchaId, bypassCookieValue);
                        return;
                    }

                    // Short bypass cookie or non-hashcash site -> treat as direct success.
                    storeCookiesFromResponse(resp);
                    org.otacoo.chan.core.site.sites.chan8.Chan8PowNotifier.onPowSolved();
                    Logger.i(TAG, "Bypass set directly (no POW required)");
                    mainHandler.post(() -> callback.onAuthenticationComplete(this, "", ""));
                    return;
                }

                Logger.w(TAG, "renewBypass unexpected status=" + status);
                showError("Bypass not established -- please try again.");
                mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
            }
        } catch (Exception e) {
            Logger.e(TAG, "submitBypass error", e);
            showError("Bypass error: " + e.getMessage());
            mainHandler.post(() -> submitButton.setEnabled(true));
        }
    }

    /**
     * Runs PBKDF2-SHA512 proof-of-work on the bypass cookie value and submits
     * the solution to validateBypass.js to activate the bypass.
     */
    private void runPowAndValidate(String captchaId, String bypassCookieValue) {
        mainHandler.post(this::startRefreshSpin);
        try {
            org.otacoo.chan.core.net.pow.LynxchanProofOfWork pow =
                    new org.otacoo.chan.core.net.pow.LynxchanProofOfWork(bypassCookieValue);
            Integer solution = pow.find();
            if (solution == null) {
                Logger.e(TAG, "POW solver returned null");
                showError("Proof-of-work solver failed -- please try again.");
                mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
                return;
            }
            Logger.i(TAG, "POW solution=" + solution + ", submitting to validateBypass...");

            // Add the pending bypass cookie to java.net so OkHttp includes it automatically.
            try {
                java.net.CookieManager cm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
                if (cm != null) {
                    java.net.URI uri = new java.net.URI(baseUrl);
                    java.net.HttpCookie hc = new java.net.HttpCookie("bypass", bypassCookieValue);
                    hc.setDomain(uri.getHost());
                    hc.setPath("/");
                    cm.getCookieStore().add(uri, hc);
                }
            } catch (Exception ignored) {}

            RequestBody powBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("code", Integer.toString(solution))
                    .build();

            Request validateReq = new Request.Builder()
                    .url(baseUrl + "validateBypass.js?json=1")
                    .post(powBody)
                    .build();

            try (Response vResp = okHttpClient.newCall(validateReq).execute()) {
                String vBody = vResp.body() != null ? vResp.body().string() : "";
                Logger.d(TAG, "validateBypass: HTTP " + vResp.code() + " body=" + vBody);
                for (String sc : vResp.headers("Set-Cookie")) {
                    Logger.i(TAG, "validateBypass Set-Cookie: " + sc);
                }

                if (!vResp.isSuccessful()) {
                    showError("POW validation failed: HTTP " + vResp.code());
                    mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
                    return;
                }

                storeCookiesFromResponse(vResp);

                JSONObject vjson = new JSONObject(vBody);
                if ("ok".equals(vjson.optString("status"))) {
                    org.otacoo.chan.core.site.sites.chan8.Chan8PowNotifier.onPowSolved();
                    Logger.i(TAG, "Bypass established after POW validation");
                    mainHandler.post(() -> {
                        Toast.makeText(getContext(), "Bypass acquired successfully.", Toast.LENGTH_SHORT).show();
                        callback.onAuthenticationComplete(this, "", "");
                    });
                } else {
                    showError("POW validation failed: " + vBody);
                    mainHandler.post(() -> { submitButton.setEnabled(true); hardReset(); });
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "runPowAndValidate error", e);
            showError("POW error: " + e.getMessage());
            mainHandler.post(() -> submitButton.setEnabled(true));
        } finally {
            mainHandler.post(this::stopRefreshSpin);
        }
    }

    private void startRefreshSpin() {
        // Swap: hide submit, show spinning refresh button
        submitButton.setVisibility(GONE);
        refreshButton.setVisibility(VISIBLE);
        if (refreshSpinner == null) {
            refreshSpinner = ObjectAnimator.ofFloat(refreshButton, "rotation", 0f, -360f);
            refreshSpinner.setDuration(800);
            refreshSpinner.setRepeatCount(ObjectAnimator.INFINITE);
            refreshSpinner.setInterpolator(new LinearInterpolator());
        }
        if (!refreshSpinner.isRunning()) refreshSpinner.start();
    }

    private void stopRefreshSpin() {
        if (refreshSpinner != null && refreshSpinner.isRunning()) {
            refreshSpinner.cancel();
            refreshButton.setRotation(0f);
        }
        // Swap back: hide refresh, show submit
        refreshButton.setVisibility(GONE);
        submitButton.setVisibility(VISIBLE);
    }

    private void storeCookiesFromResponse(Response resp) {
        // OkHttp's CookieJar already persisted Set-Cookie into java.net.
        // Push them into the WebView for any later WebView usage.
        mainHandler.post(() -> {
            android.webkit.CookieManager wvcm = android.webkit.CookieManager.getInstance();
            for (String sc : resp.headers("Set-Cookie")) {
                wvcm.setCookie(baseUrl, sc);
            }
            wvcm.flush();
        });
    }
    private void setStatus(String text) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(text);
        });
    }

    private void showError(String msg) {
        Logger.e(TAG, msg);
        setStatus(msg);
    }

    @Override
    public boolean requireResetAfterComplete() {
        // Bypass is cookie-based — no need to refetch after success.
        return false;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
