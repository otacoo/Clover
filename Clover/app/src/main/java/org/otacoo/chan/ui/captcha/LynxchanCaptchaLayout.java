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
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import javax.inject.Inject;

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

        refreshButton.setOnClickListener(v -> hardReset());
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
        currentCaptchaId = null;
        captchaInput.setText("");
        captchaImage.setImageBitmap(null);
        setStatus("Loading captcha\u2026");
        submitButton.setEnabled(false);

        String boardCode = (loadable != null && loadable.board != null)
                ? loadable.board.code : "";

        // Build the captcha fetch URL: {baseUrl}captcha.js?boardUri={board}
        // Fallback to root if no board is available.
        String url = baseUrl + "captcha.js"
                + (boardCode.isEmpty() ? "" : "?boardUri=" + boardCode);

        new Thread(() -> fetchCaptcha(url)).start();
    }

    private void fetchCaptcha(String url) {
        try {
            // 8chan returns a raw JPEG image. The captcha ID is
            // delivered via a Set-Cookie: captchaid=... response header.
            Request req = new Request.Builder()
                    .url(url)
                    .header("Accept", "image/jpeg,image/*,*/*")
                    .build();

            try (Response resp = okHttpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    showError("Captcha fetch failed: HTTP " + resp.code());
                    return;
                }

                // Extract captchaId from the Set-Cookie response header.
                String captchaId = null;
                for (String setCookie : resp.headers("Set-Cookie")) {
                    if (setCookie.startsWith("captchaid=")) {
                        captchaId = setCookie.substring("captchaid=".length()).split(";")[0].trim();
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

                // Read the raw image bytes and decode as Bitmap.
                byte[] imgBytes = resp.body().bytes();
                Logger.i(TAG, "captcha image: " + imgBytes.length + " bytes, id=" + captchaId);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) {
                    showError("Could not decode captcha image.");
                    return;
                }

                final String finalId = captchaId;
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    currentCaptchaId = finalId;
                    captchaImage.setImageBitmap(finalBmp);
                    setStatus("Type the characters shown above");
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
            setStatus("Captcha not ready — please wait or tap ↺");
            return;
        }
        AndroidUtils.hideKeyboard(captchaInput);
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
        // Force a fresh captcha after each submission so the next attempt
        // doesn't reuse a one-time token.
        return true;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
