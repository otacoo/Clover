/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
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
package org.otacoo.chan.core.exception;

import org.otacoo.chan.R;

import java.io.IOException;
import java.util.Locale;

import javax.net.ssl.SSLException;

public class ChanLoaderException extends Exception {
    private static final long serialVersionUID = 1L;

    private int statusCode = -1;

    public ChanLoaderException(String message) {
        super(message);
        if (message != null && message.startsWith("HTTP ")) {
            try {
                statusCode = Integer.parseInt(message.substring(5));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    /**
     * Returns true if the exception indicates that the user might need to authenticate
     * (e.g. due to a captcha, login requirement, or terms of service).
     */
    public boolean isVerificationRequired() {
        String message = getMessage();
        if (message == null) return false;

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("verification") ||
                lower.contains("login") ||
                lower.contains("html instead of json") ||
                lower.contains("terms of service");
    }

    public int getErrorMessage() {
        Throwable cause = getCause();

        if (cause instanceof SSLException) {
            return R.string.thread_load_failed_ssl;
        }

        if (cause instanceof IOException) {
            return R.string.thread_load_failed_network;
        }

        if (statusCode == 404) {
            return R.string.thread_load_failed_not_found;
        }

        return statusCode != -1 ? R.string.thread_load_failed_server : R.string.thread_load_failed_parsing;
    }
}
