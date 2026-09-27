package org.otacoo.chan.ui.captcha;

import static org.otacoo.chan.Chan.inject;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Handles the Lynxchan native image captcha.
 *
 * Flow:
 *   1. reset() fetches GET {baseUrl}captcha.js?boardUri={board}
 *   2. Decodes the base64 image from the JSON and shows it
 *   3. User types the text answer and presses OK or the keyboard action
 *   4. Calls callback.onAuthenticationComplete(challenge=captchaID, response=answer)
 *   5. setupPost() sends captchaId + captchaAnswer to the server
 */
public class LynxchanCaptchaLayout extends LinearLayout implements AuthenticationLayoutInterface {
    private static final String TAG = "LynxchanCaptchaLayout";

    @Inject OkHttpClient okHttpClient;

    private AuthenticationLayoutCallback callback;
    private Loadable loadable;
    private String baseUrl;

    private ImageView captchaImage;
    private EditText captchaInput;
    private Button refreshButton;
    private Button submitButton;
    private TextView statusText;

    private String currentCaptchaId;
    private boolean wasSubmitted;
    private volatile Call currentCaptchaCall;
    private volatile int captchaGeneration = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LynxchanCaptchaLayout(Context context) {
        this(context, null);
    }

    public LynxchanCaptchaLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("this-escape")
    public LynxchanCaptchaLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inject(this);

        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.layout_lynxchan_captcha, this, true);

        captchaImage   = findViewById(R.id.lynxchan_captcha_image);
        captchaInput   = findViewById(R.id.lynxchan_captcha_input);
        refreshButton  = findViewById(R.id.lynxchan_captcha_refresh);
        submitButton   = findViewById(R.id.lynxchan_captcha_submit);
        statusText     = findViewById(R.id.lynxchan_captcha_status);

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
        this.callback    = callback;
        this.loadable    = loadable;

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
        // If we're resetting right after a submit, the server rejected the answer.
        if (wasSubmitted) {
            AndroidUtils.showThemedSnackbar(this, "Wrong answer or expired captcha.", Snackbar.LENGTH_LONG);
        }
        wasSubmitted = false;

        boolean hadInflight = currentCaptchaCall != null;
        if (currentCaptchaCall != null) {
            try {
                currentCaptchaCall.cancel();
            } catch (Exception ignored) {}
            currentCaptchaCall = null;
        }
        final int generation = ++captchaGeneration;

        currentCaptchaId = null;
        captchaInput.setText("");
        captchaImage.setImageBitmap(null);
        setStatus("Loading captcha\u2026");
        submitButton.setEnabled(false);

        // Clear the old captchaid cookie so the server issues a fresh captcha.
        clearCaptchaIdCookie();

        String boardCode = (loadable != null && loadable.board != null)
                ? loadable.board.code : "";

        // Build the captcha fetch URL: {baseUrl}captcha.js?boardUri={board}
        // Fallback to root if no board is available.
        String url = baseUrl + "captcha.js"
                + (boardCode.isEmpty() ? "" : "?boardUri=" + boardCode);

        currentCaptchaCall = okHttpClient.newCall(new Request.Builder()
                .url(url)
                .header("Accept", "image/jpeg,image/*,*/*")
                .header("Cache-Control", "no-cache")
                .build());
        final Call captchaCall = currentCaptchaCall;
        Logger.i(TAG, "hardReset: generation=" + generation + " cancelledPrevious=" + hadInflight + " url=" + url);
        new Thread(() -> fetchCaptcha(captchaCall, generation, url)).start();
    }

    private void clearCaptchaIdCookie() {
        // java.net CookieStore
        java.net.CookieManager jcm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
        if (jcm != null) {
            try {
                java.net.CookieStore store = jcm.getCookieStore();
                for (java.net.URI uri : new java.util.ArrayList<>(store.getURIs())) {
                    if (uri.getHost() != null && uri.getHost().contains("8chan")) {
                        for (java.net.HttpCookie c : new java.util.ArrayList<>(store.get(uri))) {
                            if ("captchaid".equals(c.getName()) || "captchaexpiration".equals(c.getName())) {
                                store.remove(uri, c);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // android.webkit CookieManager
        mainHandler.post(() -> {
            try {
                android.webkit.CookieManager wvcm = android.webkit.CookieManager.getInstance();
                for (String d : new String[]{"https://8chan.moe/", "https://8chan.st/"}) {
                    wvcm.setCookie(d, "captchaid=; Max-Age=0; Path=/");
                    wvcm.setCookie(d, "captchaexpiration=; Max-Age=0; Path=/");
                }
                wvcm.flush();
            } catch (Exception ignored) {}
        });
    }

    private void fetchCaptcha(Call captchaCall, int generation, String url) {
        try {
            if (generation != captchaGeneration) return;
            Logger.i(TAG, "fetchCaptcha: start generation=" + generation + " url=" + url);

            // 8chan returns a raw JPEG image. The captcha ID is
            // delivered via a Set-Cookie: captchaid=... response header.
            try (Response resp = captchaCall.execute()) {
                Logger.i(TAG, "fetchCaptcha: HTTP " + resp.code() + " generation=" + generation);
                StringBuilder scNames = new StringBuilder();
                for (String sc : resp.headers("Set-Cookie")) {
                    int eq = sc.indexOf('=');
                    if (scNames.length() > 0) scNames.append(',');
                    scNames.append(eq > 0 ? sc.substring(0, eq) : sc);
                }
                Logger.i(TAG, "fetchCaptcha: Set-Cookie names=[" + scNames + "] generation=" + generation);
                if (!resp.isSuccessful()) {
                    if (generation != captchaGeneration) return;
                    showError("Captcha fetch failed: HTTP " + resp.code());
                    return;
                }

                // Extract captchaId from the Set-Cookie response header.
                String captchaId = null;
                String idSource = null;
                for (String setCookie : resp.headers("Set-Cookie")) {
                    if (setCookie.startsWith("captchaid=")) {
                        captchaId = setCookie.substring("captchaid=".length()).split(";")[0].trim();
                        idSource = "header";
                        break;
                    }
                }

                // Fallback: java.net CookieStore (written by OkHttp's AppCookieJar).
                if (captchaId == null) {
                    try {
                        java.net.CookieManager jcm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
                        if (jcm != null) {
                            java.util.List<java.net.HttpCookie> list =
                                    jcm.getCookieStore().get(new java.net.URI(url));
                            for (java.net.HttpCookie hc : list) {
                                if ("captchaid".equals(hc.getName()) && !hc.getValue().isEmpty()) {
                                    captchaId = hc.getValue();
                                    idSource = "jar";
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Last resort: WebView CookieManager.
                if (captchaId == null) {
                    try {
                        String rawCookies = android.webkit.CookieManager.getInstance().getCookie(url);
                        if (rawCookies != null) {
                            for (String part : rawCookies.split(";\\s*")) {
                                if (part.startsWith("captchaid=")) {
                                    captchaId = part.substring("captchaid=".length()).trim();
                                    idSource = "webview";
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (captchaId == null) {
                    if (generation != captchaGeneration) return;
                    showError("No captcha ID in server response.");
                    return;
                }
                Logger.i(TAG, "fetchCaptcha: idSource=" + idSource + " generation=" + generation);

                // Read the raw image bytes and decode as Bitmap.
                byte[] imgBytes = resp.body().bytes();
                Logger.i(TAG, "captcha image: " + imgBytes.length + " bytes, id=" + captchaId);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) {
                    if (generation != captchaGeneration) return;
                    int previewLen = Math.min(imgBytes.length, 64);
                    Logger.i(TAG, "fetchCaptcha: decode failed contentType=" + resp.header("Content-Type")
                            + " preview=" + new String(imgBytes, 0, previewLen, java.nio.charset.StandardCharsets.UTF_8));
                    showError("Could not decode captcha image.");
                    return;
                }
                if (generation != captchaGeneration) {
                    bmp.recycle();
                    return;
                }

                final String finalId = captchaId;
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    if (generation != captchaGeneration) {
                        finalBmp.recycle();
                        return;
                    }
                    currentCaptchaId = finalId;
                    captchaImage.setImageBitmap(finalBmp);
                    setStatus("Type the characters shown above. \nReload or tap the captcha image to fetch a new challenge.");
                    submitButton.setEnabled(true);
                    captchaInput.requestFocus();
                    AndroidUtils.requestKeyboardFocus(captchaInput);
                });
            }
        } catch (Exception e) {
            if (generation != captchaGeneration) return;
            Logger.e(TAG, "fetchCaptcha error", e);
            showError("Captcha error: " + e.getMessage());
        } finally {
            if (currentCaptchaCall == captchaCall) currentCaptchaCall = null;
        }
    }

    private void submit() {
        if (wasSubmitted) return;
        String answer = captchaInput.getText().toString().trim();
        if (answer.isEmpty()) {
            setStatus("Please enter the captcha text");
            return;
        }
        if (currentCaptchaId == null) {
            setStatus("Captcha not ready — please wait or tap ↺");
            return;
        }
        AndroidUtils.hideKeyboard(captchaInput);
        wasSubmitted = true;
        submitButton.setEnabled(false);
        Logger.i(TAG, "submit: id=" + currentCaptchaId + " answer=" + answer + " answerLen=" + answer.length());
        callback.onAuthenticationComplete(this, currentCaptchaId, answer);
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(text);
        });
    }

    private void showError(String msg) {
        Logger.e(TAG, msg);
        setStatus(msg);
        mainHandler.post(() -> {
            if (submitButton != null) submitButton.setEnabled(false);
        });
    }

    @Override
    public boolean requireResetAfterComplete() {
        // A fresh captcha is fetched on the next reset() instead.
        return false;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
