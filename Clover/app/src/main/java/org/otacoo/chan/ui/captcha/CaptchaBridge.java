/*
 * Clover - 4chan browser - https://github.com/Floens/Clover/
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
package org.otacoo.chan.ui.captcha;

import android.webkit.JavascriptInterface;

import org.otacoo.chan.utils.AndroidUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The bridge the captcha page talks to. The page is loaded from the assets
 * with the base url of 4chan, so it can reach this interface as
 * {@code CaptchaCallback}.
 */
class CaptchaBridge {

    private final NewCaptchaLayout layout;

    CaptchaBridge(NewCaptchaLayout layout) {
        this.layout = layout;
    }

    @JavascriptInterface
    public void onCaptchaLoaded() {
        AndroidUtils.runOnUiThread(layout::onCaptchaLoaded);
    }

    @JavascriptInterface
    public void onCaptchaEntered(String challenge, String response) {
        AndroidUtils.runOnUiThread(() -> layout.onCaptchaEntered(challenge, response));
    }

    @JavascriptInterface
    public void onRequestCaptcha() {
        AndroidUtils.runOnUiThread(layout::requestCaptcha);
    }

    @JavascriptInterface
    public void onCaptchaResponse(String response) {
        AndroidUtils.runOnUiThread(() -> layout.onCaptchaResponse(response));
    }

    @JavascriptInterface
    public void onAnswersChanged(String challenge, String answersCsv) {
        if (challenge != null && !challenge.isEmpty()) {
            CaptchaSession.saveAnswers(challenge, answersCsv);
        }
    }

    @JavascriptInterface
    public void onCaptchaPayloadReady(String payload) {
        try {
            String decoded = URLDecoder.decode(payload, StandardCharsets.UTF_8.name());
            AndroidUtils.runOnUiThread(() -> layout.applyPayload(decoded, layout.getUrl()));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void saveTicket(String ticket) {
        CaptchaSession.setTicket(layout.sessionKey(), ticket);
    }

    @JavascriptInterface
    public void onResetSession() {
        AndroidUtils.runOnUiThread(layout::onResetSession);
    }
}
