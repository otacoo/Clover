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

/**
 * Reads the captcha payload out of the page of 4chan.
 *
 * The captcha page sends the payload with a postMessage call, so the JSON is
 * searched behind every "postMessage" of the page.
 */
final class CaptchaPayload {

    private CaptchaPayload() {
    }

    /**
     * @param html the page of 4chan
     * @return the payload of the captcha or null when the page has none
     */
    static String extractTwister(String html) {
        if (html == null || html.contains("cf-turnstile")) return null;
        int idx = html.indexOf("postMessage");
        while (idx != -1) {
            int start = html.indexOf("{", idx);
            if (start != -1) {
                int depth = 0, i = start;
                while (i < html.length()) {
                    char c = html.charAt(i++);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    if (depth == 0) {
                        String candidate = html.substring(start, i);
                        if (candidate.contains("pcd") || candidate.contains("tasks") || candidate.contains("img")
                                || candidate.contains("ticket") || candidate.contains("cd")
                                || candidate.contains("challenge")) {
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
}
