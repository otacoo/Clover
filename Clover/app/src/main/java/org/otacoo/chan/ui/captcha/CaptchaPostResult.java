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
 * Reads the answer of 4chan to a post out of the page it answered with.
 *
 * 4chan answers a post with a page that either has the error message in the
 * "errmsg" element or the text of the result, so the page has to be read by
 * its text. When 4chan changes the wording this is the place to adjust.
 */
final class CaptchaPostResult {

    static final int UNKNOWN = 0;
    static final int SUCCESS = 1;
    static final int ERROR = 2;

    private static final String[] SUCCESS_MARKERS = {
            "Post successful"
    };

    private static final String[] ERROR_MARKERS = {
            "Error", "Verification", "mistyped"
    };

    private CaptchaPostResult() {
    }

    /**
     * @param errorMessage the text of the "errmsg" element, empty when there is none
     * @param bodyText     the text of the page
     */
    static int classify(String errorMessage, String bodyText) {
        if (errorMessage != null && !errorMessage.isEmpty() && !"null".equals(errorMessage)) {
            return ERROR;
        }
        String body = bodyText == null ? "" : bodyText;
        for (String marker : SUCCESS_MARKERS) {
            if (body.contains(marker)) return SUCCESS;
        }
        for (String marker : ERROR_MARKERS) {
            if (body.contains(marker)) return ERROR;
        }
        return UNKNOWN;
    }

    /** The error of the page, cut down to what is worth showing. */
    static String errorText(String bodyText) {
        String body = bodyText == null ? "" : bodyText;
        int start = body.indexOf("Error:");
        if (start < 0) return "Post failed or verification error.";
        int end = Math.min(start + 120, body.length());
        return body.substring(start, end).replace("\n", " ") + "...";
    }
}
