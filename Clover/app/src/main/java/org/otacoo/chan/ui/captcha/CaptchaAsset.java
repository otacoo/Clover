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

import android.content.Context;

import org.json.JSONObject;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.utils.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Renders the local captcha page: the asset page with the captcha payload,
 * the theme colors and the captcha script inlined.
 */
public final class CaptchaAsset {

    private static final String TAG = "CaptchaAsset";

    private static final String HTML_ASSET = "captcha/new_captcha.html";
    private static final String JS_ASSET = "captcha/new_captcha.js";

    private CaptchaAsset() {
    }

    /**
     * Builds the captcha page.
     *
     * @param json      the captcha payload of 4chan
     * @param singleView show all tasks of a challenge at once
     * @param autoSkip  jump to the next task after picking one
     * @return the page or null when the assets could not be read
     */
    public static String load(Context context, String json, boolean singleView, boolean autoSkip) {
        try {
            String html = readAsset(context, HTML_ASSET);
            String js = readAsset(context, JS_ASSET);
            if (html == null || js == null) return null;

            if (singleView || autoSkip) {
                json = withFlags(json, singleView, autoSkip);
            }

            boolean isDark = !ThemeHelper.theme().isLightTheme;
            ThemeHelper.ColorPair tc = ThemeHelper.getThemeBackgroundForeground(context);
            int accentCol = ThemeHelper.theme().accentColor.color;
            String accentHex = String.format("#%06X", (0xFFFFFF & accentCol));
            String accentFg = getContrastColor(accentCol);

            return html.replace("__CLOVER_JSON__", json.replace("</script>", "<\\/script>"))
                    .replace("__CLOVER_JS__", js.replace("</script", "<\\/script"))
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

    private static String withFlags(String json, boolean singleView, boolean autoSkip) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject inner = obj.optJSONObject("twister");
            JSONObject target = (inner != null) ? inner : (obj.has("tasks") ? obj : null);
            if (target != null) {
                if (singleView) target.put("single_view", true);
                if (autoSkip) target.put("auto_skip_challenge", true);
                return obj.toString();
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    private static String readAsset(Context context, String name) {
        try (InputStream is = context.getAssets().open(name);
             Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").next();
        } catch (Exception e) {
            Logger.e(TAG, "Could not read " + name, e);
            return null;
        }
    }

    private static String getContrastColor(int color) {
        double luminance = (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF)) / 255;
        return luminance > 0.5 ? "#000000" : "#ffffff";
    }

    /** Escapes a string so that it can be used inside a javascript string literal. */
    public static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
